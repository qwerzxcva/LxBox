/*
 * AIBox JNI wrapper for hev-socks5-tunnel (lightweight TUN mode).
 *
 * Bridges the core's tun fd and a YAML config straight into
 * hev_socks5_tunnel_main, on its own thread, with stop support. The
 * upstream hev-jni.c is not reused because it hard-codes the Java class
 * path and does not fit our package name.
 */
#ifdef ANDROID

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#include "hev-main.h"

static atomic_int is_running;
static pthread_t work_thread;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;

typedef struct
{
    char *config;
    int tun_fd;
} StartArgs;

static void *
thread_handler (void *data)
{
    StartArgs *args = data;
    hev_socks5_tunnel_main (args->config, args->tun_fd);
    free (args->config);
    free (args);
    atomic_store_explicit (&is_running, 0, memory_order_release);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_leadaxe_aibox_engine_vpn_HevTun_nativeStart (
    JNIEnv *env, jobject thiz, jstring config, jint tun_fd)
{
    const char *cfg;
    StartArgs *args;
    int res;

    (void)thiz;

    pthread_mutex_lock (&mutex);
    if (atomic_load_explicit (&is_running, memory_order_acquire)) {
        pthread_mutex_unlock (&mutex);
        return JNI_FALSE;
    }

    cfg = (*env)->GetStringUTFChars (env, config, NULL);
    if (!cfg) {
        pthread_mutex_unlock (&mutex);
        return JNI_FALSE;
    }

    args = calloc (1, sizeof (*args));
    if (!args) {
        (*env)->ReleaseStringUTFChars (env, config, cfg);
        pthread_mutex_unlock (&mutex);
        return JNI_FALSE;
    }
    args->config = strdup (cfg);
    args->tun_fd = (int)tun_fd;
    (*env)->ReleaseStringUTFChars (env, config, cfg);

    atomic_store_explicit (&is_running, 1, memory_order_release);
    res = pthread_create (&work_thread, NULL, thread_handler, args);
    if (res != 0) {
        atomic_store_explicit (&is_running, 0, memory_order_release);
        free (args->config);
        free (args);
        pthread_mutex_unlock (&mutex);
        return JNI_FALSE;
    }
    pthread_detach (work_thread);
    pthread_mutex_unlock (&mutex);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_leadaxe_aibox_engine_vpn_HevTun_nativeStop (JNIEnv *env, jobject thiz)
{
    jboolean result = JNI_TRUE;
    (void)env;
    (void)thiz;

    pthread_mutex_lock (&mutex);
    if (atomic_load_explicit (&is_running, memory_order_acquire)) {
        hev_socks5_tunnel_quit ();
    }
    pthread_mutex_unlock (&mutex);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_leadaxe_aibox_engine_vpn_HevTun_nativeIsRunning (
    JNIEnv *env, jobject thiz)
{
    int running;
    (void)env;
    (void)thiz;
    running = atomic_load_explicit (&is_running, memory_order_acquire);
    return running ? JNI_TRUE : JNI_FALSE;
}

#endif /* ANDROID */
