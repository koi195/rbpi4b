/*
 * android_bridge.c — a framebuffer that is a file, for a process that has no
 * /dev/fb0 and no root.
 *
 * WHY THIS EXISTS
 *
 * On the Pi, rbp draws through DirectFB into /dev/fb0. Inside an unrooted Android
 * app there is no such device and there cannot be one (proot cannot mknod a
 * character device, and Android has no fbdev for apps anyway). What the app CAN do
 * is share memory: it maps a regular file MAP_SHARED, and so does the player.
 *
 * So in Android mode (ANDROID_BRIDGE=1) the framebuffer is /tmp/rb_fb.raw, a
 * 1280x800 RGB565 file that is exactly the geometry the Pi's vc4 fb reports and
 * the geometry the patched DirectFB fbdev driver already knows how to drive
 * (one page, no panning, `off`/`convert` present path). The Android app maps the
 * same file and draws it into a SurfaceView at 60 Hz.
 *
 * HOW IT IS DONE: ONE INTERPOSED syscall()
 *
 * The framebuffer is reached from three places and none of them can be fixed by
 * editing just one:
 *
 *   - fb_shim.c's own open()/ioctl(), which go through syscalls.h's real_open()
 *     and real_ioctl() -- i.e. through libc's syscall();
 *   - the patched DirectFB fbdev driver, which reads the REAL geometry with a raw
 *     syscall(SYS_ioctl, ...) precisely so as to bypass fb_shim's lie
 *     (tools/build-directfb/README.md, item 6);
 *   - fb_cursor.c / menu_draw.c, which open and measure the fb with real_open()/
 *     real_ioctl() as well.
 *
 * All three end in libc's syscall(). Defining syscall() here (fbshim.so is first
 * in LD_PRELOAD after crashcatch/netshim, neither of which defines it) therefore
 * covers every one of them without touching DirectFB or the other modules:
 *
 *   openat/open of "/dev/fb0" (or $FB_DEV)  -> the backing file, created and
 *                                              sized on first use
 *   framebuffer ioctls on that file         -> answered here, with the Pi's
 *                                              numbers
 *   everything else                         -> forwarded untouched to libc's
 *                                              own syscall() via RTLD_NEXT
 *
 * The file is recognised by inode, not by an fd table, so dup()/fork() cannot
 * make it forget.
 *
 * WHAT IS NOT VERIFIED (no Pioneer firmware, no device here): this file compiles
 * (see `make android-check`), but it has never run under the real rbp. The
 * assumptions it rests on are listed in docs/19-android.md.
 */
#define _GNU_SOURCE
#include "android_env.h"
#include "fbdev.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

/* The packed dlsym lives in GLIBC_2.4 on the target; a modern toolchain would
 * stamp the newest. Same pin netshim.c and audioshim.c use. */
#if defined(__arm__)
__asm__(".symver dlsym, dlsym@GLIBC_2.4");
#endif

/* The geometry the Pi's vc4 fb reports, and what rbp's logical surface is. */
#define AFB_W        1280
#define AFB_H        800
#define AFB_LINE     (AFB_W * 2)          /* RGB565 */
#define AFB_LEN      (AFB_LINE * AFB_H)   /* one page: 2,048,000 bytes */

#ifndef FBIOGET_VSCREENINFO
#define FBIOGET_VSCREENINFO 0x4600
#endif
#define AFB_FBIOPUT_VSCREENINFO 0x4601
#define AFB_FBIOGET_FSCREENINFO 0x4602
#define AFB_FBIOGETCMAP         0x4604
#define AFB_FBIOPUTCMAP         0x4605
#define AFB_FBIOPAN_DISPLAY     0x4606
#define AFB_FBIOBLANK           0x4611
#define AFB_FBIO_WAITFORVSYNC   0x40044620UL

#if defined(SYS_fstat64)
#define AFB_SYS_FSTAT SYS_fstat64
typedef struct stat64 afb_stat;
#else
#define AFB_SYS_FSTAT SYS_fstat
typedef struct stat afb_stat;
#endif

static long (*g_real_syscall)(long, ...);

static long fwd(long nr, long a0, long a1, long a2, long a3, long a4, long a5)
{
    if (!g_real_syscall)
        *(void **)(&g_real_syscall) = dlsym(RTLD_NEXT, "syscall");
    if (!g_real_syscall) {
        errno = ENOSYS;
        return -1;
    }
    return g_real_syscall(nr, a0, a1, a2, a3, a4, a5);
}

/* The backing file's identity, set the first time it is opened. 0 means "not
 * opened yet", which is also "no fd can be ours". */
static unsigned long long g_fb_ino;
static unsigned long long g_fb_dev;

static int fb_path_is_fb(const char *path)
{
    if (!path)
        return 0;
    return strcmp(path, "/dev/fb0") == 0 ||
           strcmp(path, android_env("FB_DEV", "/dev/fb0")) == 0;
}

/* Open (creating, sizing) the backing file in place of the device. Returns the
 * fd or -1 with errno, exactly as the syscall it replaces. */
static long open_backing(long nr, long dirfd, long flags, long mode)
{
    const char *file = android_env("ANDROID_FB_FILE", ANDROID_FB_FILE_DEFAULT);
    long fd;
    afb_stat st;

    flags = (flags & ~(long)(O_WRONLY | O_RDWR | O_TRUNC)) | O_RDWR | O_CREAT;
    if (nr == SYS_openat)
        fd = fwd(SYS_openat, dirfd, (long)file, flags, 0666, 0, 0);
    else
        fd = fwd(SYS_open, (long)file, flags, 0666, 0, 0, 0);
    (void)mode;
    if (fd < 0)
        return fd;

    /* ftruncate to the page size is idempotent (same size keeps the contents) and
     * makes the mapping the driver is about to take valid end to end: touching a
     * page past EOF of a MAP_SHARED file is a SIGBUS, not a zero. */
    if (fwd(SYS_ftruncate, fd, AFB_LEN, 0, 0, 0, 0) != 0) {
        long e = errno;
        fwd(SYS_close, fd, 0, 0, 0, 0, 0);
        errno = (int)e;
        return -1;
    }
    memset(&st, 0, sizeof st);
    if (fwd(AFB_SYS_FSTAT, fd, (long)&st, 0, 0, 0, 0) == 0) {
        g_fb_ino = (unsigned long long)st.st_ino;
        g_fb_dev = (unsigned long long)st.st_dev;
    }
    return fd;
}

static int fb_is_fd(int fd)
{
    afb_stat st;

    if (g_fb_ino == 0 || fd < 0)
        return 0;
    memset(&st, 0, sizeof st);
    if (fwd(AFB_SYS_FSTAT, fd, (long)&st, 0, 0, 0, 0) != 0)
        return 0;
    return (unsigned long long)st.st_ino == g_fb_ino &&
           (unsigned long long)st.st_dev == g_fb_dev;
}

/* One 60 Hz frame boundary. The real vc4 blocks the caller until vblank, and that
 * block is what paces rbp's render thread (fb_shim.c measures it at 57/s). A file
 * has no vblank, so the pacing is done here against a monotonic grid; without it
 * rbp would render as fast as it can and burn the tablet's battery for frames the
 * SurfaceView will never show. */
static void frame_wait(void)
{
    static struct timespec next;
    struct timespec now;
    const long period_ns = 16666667L;

    clock_gettime(CLOCK_MONOTONIC, &now);
    if (next.tv_sec == 0 && next.tv_nsec == 0)
        next = now;
    next.tv_nsec += period_ns;
    if (next.tv_nsec >= 1000000000L) {
        next.tv_sec += 1;
        next.tv_nsec -= 1000000000L;
    }
    if (now.tv_sec > next.tv_sec ||
        (now.tv_sec == next.tv_sec && now.tv_nsec >= next.tv_nsec)) {
        /* Running late (the process was stopped, or a frame took long): restart
         * the grid instead of sprinting to catch up. */
        next = now;
        return;
    }
    clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
}

static long fb_ioctl(unsigned long req, void *arg)
{
    switch (req) {
    case FBIOGET_VSCREENINFO: {
        struct fb_var_screeninfo *v = arg;
        if (!v) { errno = EFAULT; return -1; }
        memset(v, 0, sizeof *v);
        v->xres = AFB_W;          v->yres = AFB_H;
        v->xres_virtual = AFB_W;  v->yres_virtual = AFB_H;
        v->bits_per_pixel = 16;
        v->red.offset = 11;   v->red.length = 5;
        v->green.offset = 5;  v->green.length = 6;
        v->blue.offset = 0;   v->blue.length = 5;
        v->height = 125;      v->width = 200;      /* mm; nothing reads them */
        v->pixclock = 13889;                       /* ~72 MHz: nonzero on purpose */
        return 0;
    }
    case AFB_FBIOGET_FSCREENINFO: {
        struct fb_fix_screeninfo *f = arg;
        if (!f) { errno = EFAULT; return -1; }
        memset(f, 0, sizeof *f);
        memcpy(f->id, "rbandroid", 9);
        f->smem_len = AFB_LEN;
        f->type = 0;                               /* FB_TYPE_PACKED_PIXELS */
        f->visual = 2;                             /* FB_VISUAL_TRUECOLOR */
        f->xpanstep = 1;                           /* what vc4 reports; the page */
        f->ypanstep = 1;                           /* count, not this, decides   */
        f->line_length = AFB_LINE;
        return 0;
    }
    case AFB_FBIOPUT_VSCREENINFO:
    case AFB_FBIOGETCMAP:
    case AFB_FBIOPUTCMAP:
    case AFB_FBIOBLANK:
        return 0;
    case AFB_FBIOPAN_DISPLAY:
    case AFB_FBIO_WAITFORVSYNC:
        frame_wait();
        return 0;
    default:
        errno = ENOTTY;
        return -1;
    }
}

/*
 * The interposed syscall(). Variadic because libc's is; six arguments is the
 * most a Linux syscall takes, and reading more than the caller passed is the
 * same harmless over-read libc's own wrapper does.
 *
 * Android mode off => a pure forward, and on the Pi this file is not even linked
 * in unless the Makefile's android rule asks for it (see FBSHIM_OBJS).
 */
long syscall(long nr, ...)
{
    va_list ap;
    long a[6];
    int i;

    va_start(ap, nr);
    for (i = 0; i < 6; i++)
        a[i] = va_arg(ap, long);
    va_end(ap);

    if (android_mode()) {
        if (nr == SYS_openat && fb_path_is_fb((const char *)a[1]))
            return open_backing(nr, a[0], a[2], a[3]);
#if defined(SYS_open)
        if (nr == SYS_open && fb_path_is_fb((const char *)a[0]))
            return open_backing(nr, 0, a[1], a[2]);
#endif
        if (nr == SYS_ioctl && ((a[1] >> 8) & 0xff) == 'F' &&
            fb_is_fd((int)a[0]))
            return fb_ioctl((unsigned long)a[1], (void *)a[2]);
    }
    return fwd(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
}
