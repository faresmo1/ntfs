#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <android/log.h>

#include "ntfs-3g/types.h"
#include "ntfs-3g/volume.h"
#include "ntfs-3g/device.h"
#include "ntfs-3g/dir.h"
#include "ntfs-3g/inode.h"
#include "ntfs-3g/attrib.h"
#include "ntfs-3g/unistr.h"
#include "ntfs-3g/layout.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ntfsjni", __VA_ARGS__)
#define CHUNK (256 * 1024)
#define BLKGETSIZE64_REQ 0x80081272UL

typedef struct {
    JavaVM *jvm;
    jobject self;
    jmethodID m_read;
    jmethodID m_write;
    jbyteArray arr;
    s64 size;
    s64 pos;
} dev_ctx;

typedef struct {
    ntfs_inode *ni;
    ntfs_attr *na;
    int writing;
} file_h;

static JNIEnv *get_env(dev_ctx *c) {
    JNIEnv *env = NULL;
    if ((*c->jvm)->GetEnv(c->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED)
        (*c->jvm)->AttachCurrentThread(c->jvm, &env, NULL);
    return env;
}

static int d_open(struct ntfs_device *dev, int flags) {
    NDevSetOpen(dev);
    if ((flags & O_ACCMODE) == O_RDONLY) NDevSetReadOnly(dev);
    return 0;
}

static int d_close(struct ntfs_device *dev) {
    NDevClearOpen(dev);
    return 0;
}

static s64 d_seek(struct ntfs_device *dev, s64 off, int whence) {
    dev_ctx *c = (dev_ctx *)dev->d_private;
    switch (whence) {
        case SEEK_SET: c->pos = off; break;
        case SEEK_CUR: c->pos += off; break;
        case SEEK_END: c->pos = c->size + off; break;
        default: errno = EINVAL; return -1;
    }
    return c->pos;
}

static s64 d_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset) {
    dev_ctx *c = (dev_ctx *)dev->d_private;
    JNIEnv *env = get_env(c);
    if (!env) { errno = EIO; return -1; }
    s64 done = 0;
    while (done < count) {
        jint n = (jint)((count - done) > CHUNK ? CHUNK : (count - done));
        jint r = (*env)->CallIntMethod(env, c->self, c->m_read, (jlong)(offset + done), c->arr, n);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); errno = EIO; return -1; }
        if (r < n) { errno = EIO; return -1; }
        (*env)->GetByteArrayRegion(env, c->arr, 0, n, (jbyte *)((char *)buf + done));
        done += n;
    }
    return done;
}

static s64 d_pwrite(struct ntfs_device *dev, const void *buf, s64 count, s64 offset) {
    dev_ctx *c = (dev_ctx *)dev->d_private;
    JNIEnv *env = get_env(c);
    if (!env) { errno = EIO; return -1; }
    s64 done = 0;
    while (done < count) {
        jint n = (jint)((count - done) > CHUNK ? CHUNK : (count - done));
        (*env)->SetByteArrayRegion(env, c->arr, 0, n, (const jbyte *)((const char *)buf + done));
        jint r = (*env)->CallIntMethod(env, c->self, c->m_write, (jlong)(offset + done), c->arr, n);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); errno = EIO; return -1; }
        if (r < n) { errno = EIO; return -1; }
        done += n;
    }
    return done;
}

static s64 d_read(struct ntfs_device *dev, void *buf, s64 count) {
    dev_ctx *c = (dev_ctx *)dev->d_private;
    s64 r = d_pread(dev, buf, count, c->pos);
    if (r > 0) c->pos += r;
    return r;
}

static s64 d_write(struct ntfs_device *dev, const void *buf, s64 count) {
    dev_ctx *c = (dev_ctx *)dev->d_private;
    s64 r = d_pwrite(dev, buf, count, c->pos);
    if (r > 0) c->pos += r;
    return r;
}

static int d_sync(struct ntfs_device *dev) { return 0; }

static int d_stat(struct ntfs_device *dev, struct stat *st) {
    dev_ctx *c = (dev_ctx *)dev->d_private;
    memset(st, 0, sizeof(*st));
    st->st_mode = S_IFREG | 0600;
    st->st_size = c->size;
    return 0;
}

static int d_ioctl(struct ntfs_device *dev, int request, void *argp) {
    dev_ctx *c = (dev_ctx *)dev->d_private;
    if ((unsigned long)request == BLKGETSIZE64_REQ) {
        *(u64 *)argp = (u64)c->size;
        return 0;
    }
    errno = ENOTTY;
    return -1;
}

static struct ntfs_device_operations dops = {
    .open = d_open, .close = d_close, .seek = d_seek, .read = d_read, .write = d_write,
    .pread = d_pread, .pwrite = d_pwrite, .sync = d_sync, .stat = d_stat, .ioctl = d_ioctl,
};

static char *jpath(JNIEnv *env, jbyteArray a) {
    jsize n = (*env)->GetArrayLength(env, a);
    char *s = (char *)malloc((size_t)n + 1);
    if (!s) return NULL;
    (*env)->GetByteArrayRegion(env, a, 0, n, (jbyte *)s);
    s[n] = 0;
    return s;
}

static void split_path(const char *path, char **dir, char **name) {
    const char *slash = strrchr(path, '/');
    if (!slash || slash == path) {
        *dir = strdup("/");
        *name = strdup(slash ? slash + 1 : path);
        return;
    }
    *dir = strndup(path, (size_t)(slash - path));
    *name = strdup(slash + 1);
}

static s64 ntfs_to_ms(sle64 t) {
    s64 v = sle64_to_cpu(t);
    return (v - 116444736000000000LL) / 10000;
}

typedef struct {
    ntfschar *name;
    int len;
    MFT_REF mref;
    unsigned dt;
} dirent_t;

typedef struct { dirent_t *v; int n, cap; } dirlist_t;

static int filldir(void *d, const ntfschar *name, const int name_len, const int name_type,
                   const s64 pos, const MFT_REF mref, const unsigned dt_type) {
    dirlist_t *l = (dirlist_t *)d;
    if (name_type == FILE_NAME_DOS) return 0;
    if (name_len == 1 && name[0] == const_cpu_to_le16('.')) return 0;
    if (name_len == 2 && name[0] == const_cpu_to_le16('.') && name[1] == const_cpu_to_le16('.')) return 0;
    if (MREF(mref) < 24 && MREF(mref) != 5) return 0;
    if (l->n == l->cap) {
        l->cap = l->cap ? l->cap * 2 : 64;
        l->v = (dirent_t *)realloc(l->v, (size_t)l->cap * sizeof(dirent_t));
        if (!l->v) return -1;
    }
    ntfschar *copy = (ntfschar *)malloc((size_t)name_len * sizeof(ntfschar));
    if (!copy) return -1;
    memcpy(copy, name, (size_t)name_len * sizeof(ntfschar));
    l->v[l->n].name = copy;
    l->v[l->n].len = name_len;
    l->v[l->n].mref = mref;
    l->v[l->n].dt = dt_type;
    l->n++;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeMount(JNIEnv *env, jobject thiz, jobject self, jlong size, jboolean ro) {
    static int inited = 0;
    if (!inited) { ntfs_set_char_encoding("UTF-8"); inited = 1; }

    dev_ctx *c = (dev_ctx *)calloc(1, sizeof(*c));
    if (!c) return -3;
    (*env)->GetJavaVM(env, &c->jvm);
    c->self = (*env)->NewGlobalRef(env, self);
    jclass cls = (*env)->GetObjectClass(env, self);
    c->m_read = (*env)->GetMethodID(env, cls, "devRead", "(J[BI)I");
    c->m_write = (*env)->GetMethodID(env, cls, "devWrite", "(J[BI)I");
    jbyteArray a = (*env)->NewByteArray(env, CHUNK);
    c->arr = (jbyteArray)(*env)->NewGlobalRef(env, a);
    c->size = size;

    struct ntfs_device *dev = ntfs_device_alloc("usb-ntfs", 0, &dops, c);
    if (!dev) { free(c); return -3; }
    ntfs_mount_flags fl = ro ? NTFS_MNT_RDONLY : NTFS_MNT_NONE;
    ntfs_volume *vol = ntfs_device_mount(dev, fl);
    if (!vol) {
        int e = errno;
        LOG("mount failed errno=%d", e);
        ntfs_device_free(dev);
        (*env)->DeleteGlobalRef(env, c->self);
        (*env)->DeleteGlobalRef(env, c->arr);
        free(c);
        if (e == EOPNOTSUPP) return -1;
        if (e == EPERM) return -2;
        return -3;
    }
    return (jlong)(intptr_t)vol;
}

JNIEXPORT void JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeUnmount(JNIEnv *env, jobject thiz, jlong v) {
    ntfs_volume *vol = (ntfs_volume *)(intptr_t)v;
    if (!vol) return;
    struct ntfs_device *dev = vol->dev;
    dev_ctx *c = (dev_ctx *)dev->d_private;
    ntfs_umount(vol, 1);
    (*env)->DeleteGlobalRef(env, c->self);
    (*env)->DeleteGlobalRef(env, c->arr);
    free(c);
}

JNIEXPORT jlong JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeTotal(JNIEnv *env, jobject thiz, jlong v) {
    ntfs_volume *vol = (ntfs_volume *)(intptr_t)v;
    return (jlong)vol->nr_clusters * vol->cluster_size;
}

JNIEXPORT jlong JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeFree(JNIEnv *env, jobject thiz, jlong v) {
    ntfs_volume *vol = (ntfs_volume *)(intptr_t)v;
    if (ntfs_volume_get_free_space(vol) != 0) return 0;
    return (jlong)vol->nr_free_clusters * vol->cluster_size;
}

static jstring info_string(JNIEnv *env, const ntfschar *name, int len, int is_dir, s64 size, s64 mtime) {
    char tail[96];
    int tl = snprintf(tail, sizeof tail, "\x1F%d\x1F%lld\x1F%lld", is_dir, (long long)size, (long long)mtime);
    jchar *buf = (jchar *)malloc(((size_t)len + (size_t)tl) * sizeof(jchar));
    if (!buf) return NULL;
    for (int i = 0; i < len; i++) buf[i] = (jchar)le16_to_cpu(name[i]);
    for (int i = 0; i < tl; i++) buf[len + i] = (jchar)(unsigned char)tail[i];
    jstring s = (*env)->NewString(env, buf, len + tl);
    free(buf);
    return s;
}

JNIEXPORT jobjectArray JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeList(JNIEnv *env, jobject thiz, jlong v, jbyteArray jp) {
    ntfs_volume *vol = (ntfs_volume *)(intptr_t)v;
    char *path = jpath(env, jp);
    ntfs_inode *dir = ntfs_pathname_to_inode(vol, NULL, path);
    free(path);
    if (!dir) return NULL;
    dirlist_t l = {0};
    s64 pos = 0;
    int rc = ntfs_readdir(dir, &pos, &l, filldir);
    ntfs_inode_close(dir);
    jclass sc = (*env)->FindClass(env, "java/lang/String");
    jobjectArray out = NULL;
    if (rc == 0) {
        out = (*env)->NewObjectArray(env, l.n, sc, NULL);
        for (int i = 0; i < l.n; i++) {
            int is_dir = l.v[i].dt == 4;
            s64 size = 0, mt = 0;
            ntfs_inode *ni = ntfs_inode_open(vol, l.v[i].mref);
            if (ni) {
                if (!is_dir) size = ni->data_size;
                mt = ntfs_to_ms(ni->last_data_change_time);
                ntfs_inode_close(ni);
            }
            jstring js = info_string(env, l.v[i].name, l.v[i].len, is_dir, size, mt);
            (*env)->SetObjectArrayElement(env, out, i, js);
            (*env)->DeleteLocalRef(env, js);
        }
    }
    for (int i = 0; i < l.n; i++) free(l.v[i].name);
    free(l.v);
    return out;
}

static int do_create(ntfs_volume *vol, const char *path, int dir) {
    char *d, *n;
    split_path(path, &d, &n);
    int rc = 0;
    ntfschar *un = NULL;
    int ul = ntfs_mbstoucs(n, &un);
    ntfs_inode *dni = ntfs_pathname_to_inode(vol, NULL, d);
    if (ul <= 0 || ul > 255 || !dni) { rc = -(errno ? errno : EINVAL); }
    else {
        ntfs_inode *ni = ntfs_create(dni, 0, un, (u8)ul, dir ? S_IFDIR : S_IFREG);
        if (!ni) rc = -(errno ? errno : EIO); else ntfs_inode_close(ni);
    }
    if (dni) ntfs_inode_close(dni);
    free(un); free(d); free(n);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeCreate(JNIEnv *env, jobject thiz, jlong v, jbyteArray jp, jboolean dir) {
    char *p = jpath(env, jp);
    int rc = do_create((ntfs_volume *)(intptr_t)v, p, dir);
    free(p);
    return rc;
}

static int do_unlink(ntfs_volume *vol, const char *path) {
    char *d, *n;
    split_path(path, &d, &n);
    ntfschar *un = NULL;
    int ul = ntfs_mbstoucs(n, &un);
    ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path);
    ntfs_inode *dni = ntfs_pathname_to_inode(vol, NULL, d);
    int rc = 0;
    if (ul <= 0 || !ni || !dni) {
        rc = -(errno ? errno : ENOENT);
        if (ni) ntfs_inode_close(ni);
        if (dni) ntfs_inode_close(dni);
    } else if (ntfs_delete(vol, path, ni, dni, un, (u8)ul) != 0) {
        rc = -(errno ? errno : EIO);
    }
    free(un); free(d); free(n);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeUnlink(JNIEnv *env, jobject thiz, jlong v, jbyteArray jp) {
    char *p = jpath(env, jp);
    int rc = do_unlink((ntfs_volume *)(intptr_t)v, p);
    free(p);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeRename(JNIEnv *env, jobject thiz, jlong v, jbyteArray jf, jbyteArray jt) {
    ntfs_volume *vol = (ntfs_volume *)(intptr_t)v;
    char *from = jpath(env, jf), *to = jpath(env, jt);
    int rc = 0;
    char *td, *tn;
    split_path(to, &td, &tn);
    ntfschar *un = NULL;
    int ul = ntfs_mbstoucs(tn, &un);
    ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, from);
    ntfs_inode *dni = ntfs_pathname_to_inode(vol, NULL, td);
    if (ul <= 0 || ul > 255 || !ni || !dni) {
        rc = -(errno ? errno : ENOENT);
    } else if (ntfs_link(ni, dni, un, (u8)ul) != 0) {
        rc = -(errno ? errno : EIO);
    }
    if (ni) ntfs_inode_close(ni);
    if (dni) ntfs_inode_close(dni);
    free(un); free(td); free(tn);
    if (rc == 0) rc = do_unlink(vol, from);
    free(from); free(to);
    return rc;
}

JNIEXPORT jlong JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeOpen(JNIEnv *env, jobject thiz, jlong v, jbyteArray jp,
                                            jboolean write, jboolean truncate) {
    ntfs_volume *vol = (ntfs_volume *)(intptr_t)v;
    char *path = jpath(env, jp);
    ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path);
    if (!ni && write) {
        if (do_create(vol, path, 0) == 0) ni = ntfs_pathname_to_inode(vol, NULL, path);
    }
    free(path);
    if (!ni) return 0;
    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) { ntfs_inode_close(ni); return 0; }
    if (write && truncate && na->data_size != 0) {
        if (ntfs_attr_truncate(na, 0) != 0) { ntfs_attr_close(na); ntfs_inode_close(ni); return 0; }
    }
    file_h *h = (file_h *)calloc(1, sizeof(*h));
    h->ni = ni; h->na = na; h->writing = write;
    return (jlong)(intptr_t)h;
}

JNIEXPORT jint JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeRead(JNIEnv *env, jobject thiz, jlong hv, jlong off, jbyteArray buf, jint len) {
    file_h *h = (file_h *)(intptr_t)hv;
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    s64 r = ntfs_attr_pread(h->na, off, len, p);
    (*env)->ReleaseByteArrayElements(env, buf, p, 0);
    return (jint)r;
}

JNIEXPORT jint JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeWrite(JNIEnv *env, jobject thiz, jlong hv, jlong off, jbyteArray buf, jint len) {
    file_h *h = (file_h *)(intptr_t)hv;
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    s64 r = ntfs_attr_pwrite(h->na, off, len, p);
    (*env)->ReleaseByteArrayElements(env, buf, p, JNI_ABORT);
    return (jint)r;
}

JNIEXPORT void JNICALL
Java_com_otgfiles_fs_NtfsBackend_nativeClose(JNIEnv *env, jobject thiz, jlong hv) {
    file_h *h = (file_h *)(intptr_t)hv;
    ntfs_attr_close(h->na);
    if (h->writing) ntfs_inode_update_times(h->ni, NTFS_UPDATE_MTIME | NTFS_UPDATE_CTIME);
    ntfs_inode_close(h->ni);
    free(h);
}
