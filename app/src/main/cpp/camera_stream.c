#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <unistd.h>

__attribute__((constructor)) static void init_stream(void) {
    struct stat st;
    if (fstat(3, &st) || !S_ISFIFO(st.st_mode)) { fprintf(stderr, "Camera stream: fd 3 is not a pipe (errno %d)\n", errno); _exit(73); }
    if (prctl(PR_SET_PDEATHSIG, SIGTERM)) { perror("Camera stream: prctl"); _exit(74); }
    if (getppid() == 1) { fprintf(stderr, "Camera stream: parent already exited\n"); _exit(75); }
}

static int is_dump_write_mode(const char *mode) {
    return mode && (!strcmp(mode, "w") || !strcmp(mode, "wb")
            || !strcmp(mode, "w+") || !strcmp(mode, "wb+")
            || !strcmp(mode, "w+b"));
}

FILE *fopen(const char *path, const char *mode) {
    static FILE *(*original)(const char *, const char *);
    const char prefix[] = "/data/vendor/camera/frame_0_";
    if (strncmp(path, prefix, sizeof(prefix)-1) == 0) {
        const char *p = path + sizeof(prefix)-1;
        if (*p < '0' || *p > '9') { errno = EINVAL; return NULL; }
        while (*p >= '0' && *p <= '9') p++;
        if (strcmp(p, ".raw") || !is_dump_write_mode(mode)) { errno = EINVAL; return NULL; }
        int fd = dup(3);
        if (fd < 0) return NULL;
        FILE *stream = fdopen(fd, "w");
        if (!stream) close(fd);
        return stream;
    }
    if (!original) original = dlsym(RTLD_NEXT, "fopen");
    if (!original) { errno = ENOSYS; return NULL; }
    return original(path, mode);
}
