#include <jni.h>
#include <errno.h>
#include <sys/socket.h>
#include <unistd.h>

static int probe = -1;
JNIEXPORT jint JNICALL Java_dev_beampro_port_GlassesNetworkDeviceChecks_socketMark(JNIEnv *env, jclass type, jboolean create) {
    (void)env; (void)type;
    if (create) {
        if (probe >= 0) close(probe);
        probe = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    }
    unsigned int mark = 0; socklen_t size = sizeof(mark);
    if (probe < 0 || getsockopt(probe, SOL_SOCKET, SO_MARK, &mark, &size)) return -errno;
    return (jint)mark;
}
JNIEXPORT void JNICALL Java_dev_beampro_port_GlassesNetworkDeviceChecks_closeSocket(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    if (probe >= 0) close(probe);
    probe = -1;
}
