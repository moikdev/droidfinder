#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Only this operation is native: Android's public Os API lacks RENAME_NOREPLACE.
 * UTF-8 byte arrays preserve non-BMP filenames without JNI's modified UTF-8.
 * Return errno to Kotlin; never fall back to a replacing rename. */
static char *copy_path(JNIEnv *env, jbyteArray value, int *error) {
    if (value == NULL) { *error = EINVAL; return NULL; }
    jsize size = (*env)->GetArrayLength(env, value);
    if (size < 1 || size > 16384) { *error = ENAMETOOLONG; return NULL; }
    char *path = malloc((size_t)size + 1);
    if (path == NULL) { *error = ENOMEM; return NULL; }
    (*env)->GetByteArrayRegion(env, value, 0, size, (jbyte *)path);
    if ((*env)->ExceptionCheck(env)) { free(path); *error = EINVAL; return NULL; }
    if (memchr(path, '\0', (size_t)size) != NULL) { free(path); *error = EINVAL; return NULL; }
    path[size] = '\0';
    return path;
}

JNIEXPORT jint JNICALL Java_dev_findroid_NativeFilePublication_renameNoReplace(
    JNIEnv *env, jobject instance, jbyteArray from, jbyteArray to) {
    (void)instance;
    int error = 0;
    char *source = copy_path(env, from, &error);
    if (source == NULL) return error;
    char *target = copy_path(env, to, &error);
    if (target == NULL) { free(source); return error; }
#ifdef __NR_renameat2
    /* RENAME_NOREPLACE = 1: EEXIST keeps both original names untouched. */
    long result = syscall(__NR_renameat2, AT_FDCWD, source, AT_FDCWD, target, 1U);
    error = result == 0 ? 0 : errno;
#else
    error = ENOSYS;
#endif
    free(source);
    free(target);
    return error;
}
