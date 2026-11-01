// The bridge between Kotlin and PDFium. It runs only inside the isolated ":pdf" process
// (ua.hramota.pdf.PdfEngineService): that process has no permissions and cannot open files;
// the document arrives as an already open file descriptor, pages leave through shared memory.
// PDFium is not thread-safe: the Kotlin side calls these functions under one lock.

#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "fpdfview.h"
#include "fpdf_attachment.h"
#include "fpdf_doc.h"
#include "fpdf_formfill.h"
#include "fpdf_javascript.h"
#include "fpdf_text.h"

typedef struct {
    int fd;
    FPDF_FILEACCESS access;
    FPDF_DOCUMENT doc;
} Doc;

static int read_block(void* param, unsigned long position, unsigned char* buf, unsigned long size) {
    Doc* d = (Doc*)param;
    unsigned long done = 0;
    while (done < size) {
        ssize_t n = pread(d->fd, buf + done, size - done, (off_t)(position + done));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        done += (unsigned long)n;
    }
    return 1;
}

JNIEXPORT void JNICALL Java_ua_hramota_pdf_PdfiumNative_init(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    FPDF_LIBRARY_CONFIG config;
    memset(&config, 0, sizeof(config));
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
}

// Returns a document handle, or a negative PDFium error code (-4 = needs a password).
JNIEXPORT jlong JNICALL Java_ua_hramota_pdf_PdfiumNative_open(JNIEnv* env, jclass cls, jint fd, jstring password) {
    (void)cls;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) return -FPDF_ERR_FILE;
    Doc* d = calloc(1, sizeof(Doc));
    if (!d) return -FPDF_ERR_UNKNOWN;
    d->fd = dup(fd);
    if (d->fd < 0) { free(d); return -FPDF_ERR_FILE; }
    d->access.m_FileLen = (unsigned long)st.st_size;
    d->access.m_GetBlock = read_block;
    d->access.m_Param = d;
    const char* pw = password ? (*env)->GetStringUTFChars(env, password, NULL) : NULL;
    d->doc = FPDF_LoadCustomDocument(&d->access, pw);
    if (pw) (*env)->ReleaseStringUTFChars(env, password, pw);
    if (!d->doc) {
        long err = (long)FPDF_GetLastError();
        close(d->fd);
        free(d);
        return -(err > 0 ? err : FPDF_ERR_UNKNOWN);
    }
    return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL Java_ua_hramota_pdf_PdfiumNative_close(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    Doc* d = (Doc*)(intptr_t)handle;
    if (!d) return;
    FPDF_CloseDocument(d->doc);
    close(d->fd);
    free(d);
}

JNIEXPORT jint JNICALL Java_ua_hramota_pdf_PdfiumNative_pageCount(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    return FPDF_GetPageCount(((Doc*)(intptr_t)handle)->doc);
}

// Page sizes in points, as [w0, h0, w1, h1, ...].
JNIEXPORT jfloatArray JNICALL Java_ua_hramota_pdf_PdfiumNative_pageSizes(JNIEnv* env, jclass cls, jlong handle) {
    (void)cls;
    FPDF_DOCUMENT doc = ((Doc*)(intptr_t)handle)->doc;
    int n = FPDF_GetPageCount(doc);
    if (n < 0) n = 0;
    jfloatArray out = (*env)->NewFloatArray(env, n * 2);
    if (!out) return NULL;
    jfloat* v = (*env)->GetFloatArrayElements(env, out, NULL);
    if (!v) return NULL;
    for (int i = 0; i < n; i++) {
        FS_SIZEF size = {0, 0};
        FPDF_GetPageSizeByIndexF(doc, i, &size);
        v[2 * i] = size.width;
        v[2 * i + 1] = size.height;
    }
    (*env)->ReleaseFloatArrayElements(env, out, v, 0);
    return out;
}

// Draws page [index] into [buffer] (direct, at least stride * height bytes) as RGBA, white background.
JNIEXPORT jboolean JNICALL Java_ua_hramota_pdf_PdfiumNative_render(JNIEnv* env, jclass cls, jlong handle, jint index,
                                                                   jobject buffer, jint width, jint height, jint stride) {
    (void)cls;
    void* pixels = (*env)->GetDirectBufferAddress(env, buffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!pixels || width <= 0 || height <= 0 || width > 16384 || height > 16384) return JNI_FALSE;
    if (stride < width * 4 || capacity < (jlong)stride * height) return JNI_FALSE;
    FPDF_DOCUMENT doc = ((Doc*)(intptr_t)handle)->doc;
    FPDF_PAGE page = FPDF_LoadPage(doc, index);
    if (!page) return JNI_FALSE;
    FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, pixels, stride);
    if (!bitmap) { FPDF_ClosePage(page); return JNI_FALSE; }
    FPDFBitmap_FillRect(bitmap, 0, 0, width, height, 0xFFFFFFFF);
    // Annotations drawn as their stored appearance; RGBA byte order, as Android's ARGB_8888 expects
    FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0, FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER);
    FPDFBitmap_Destroy(bitmap);
    FPDF_ClosePage(page);
    return JNI_TRUE;
}

static jstring utf16(JNIEnv* env, const unsigned short* buf, unsigned long bytes) {
    // PDFium returns UTF-16LE with a terminating zero
    jsize len = bytes >= 2 ? (jsize)(bytes / 2 - 1) : 0;
    return (*env)->NewString(env, (const jchar*)buf, len);
}

static jstring meta(JNIEnv* env, FPDF_DOCUMENT doc, const char* tag) {
    unsigned long n = FPDF_GetMetaText(doc, tag, NULL, 0);
    if (n <= 2 || n > 4096) return NULL;
    unsigned short* buf = malloc(n);
    if (!buf) return NULL;
    FPDF_GetMetaText(doc, tag, buf, n);
    jstring s = utf16(env, buf, n);
    free(buf);
    return s;
}

// What the document carries, for the inspector:
// [javascript count, attachments count, form type, uri links, launch actions, author, creator, producer, attachment names...]
JNIEXPORT jobjectArray JNICALL Java_ua_hramota_pdf_PdfiumNative_inspect(JNIEnv* env, jclass cls, jlong handle, jint maxPages) {
    (void)cls;
    FPDF_DOCUMENT doc = ((Doc*)(intptr_t)handle)->doc;
    int attachments = FPDFDoc_GetAttachmentCount(doc);
    if (attachments < 0) attachments = 0;
    int shownAttachments = attachments < 50 ? attachments : 50;
    int uri = 0, launch = 0;
    int pages = FPDF_GetPageCount(doc);
    if (pages > maxPages) pages = maxPages;
    for (int i = 0; i < pages; i++) {
        FPDF_PAGE page = FPDF_LoadPage(doc, i);
        if (!page) continue;
        int pos = 0;
        FPDF_LINK link;
        int guard = 0;
        while (guard++ < 10000 && FPDFLink_Enumerate(page, &pos, &link)) {
            FPDF_ACTION action = FPDFLink_GetAction(link);
            if (!action) continue;
            unsigned long type = FPDFAction_GetType(action);
            if (type == PDFACTION_URI) uri++;
            else if (type == PDFACTION_LAUNCH) launch++;
        }
        FPDF_ClosePage(page);
    }
    jclass str = (*env)->FindClass(env, "java/lang/String");
    if (!str) return NULL;
    jobjectArray out = (*env)->NewObjectArray(env, 8 + shownAttachments, str, NULL);
    if (!out) return NULL;
    char num[32];
    int values[5] = {FPDFDoc_GetJavaScriptActionCount(doc), attachments, FPDF_GetFormType(doc), uri, launch};
    for (int i = 0; i < 5; i++) {
        snprintf(num, sizeof(num), "%d", values[i]);
        (*env)->SetObjectArrayElement(env, out, i, (*env)->NewStringUTF(env, num));
    }
    (*env)->SetObjectArrayElement(env, out, 5, meta(env, doc, "Author"));
    (*env)->SetObjectArrayElement(env, out, 6, meta(env, doc, "Creator"));
    (*env)->SetObjectArrayElement(env, out, 7, meta(env, doc, "Producer"));
    for (int i = 0; i < shownAttachments; i++) {
        FPDF_ATTACHMENT a = FPDFDoc_GetAttachment(doc, i);
        if (!a) continue;
        unsigned long n = FPDFAttachment_GetName(a, NULL, 0);
        if (n <= 2 || n > 2048) continue;
        unsigned short* buf = malloc(n);
        if (!buf) continue;
        FPDFAttachment_GetName(a, buf, n);
        (*env)->SetObjectArrayElement(env, out, 8 + i, utf16(env, buf, n));
        free(buf);
    }
    return out;
}

// The text of page [index], one UTF-16 unit per PDFium character, so a position in the string is a
// character index on the page (characters outside the BMP become U+FFFD). At most maxChars.
JNIEXPORT jstring JNICALL Java_ua_hramota_pdf_PdfiumNative_text(JNIEnv* env, jclass cls, jlong handle, jint index, jint maxChars) {
    (void)cls;
    FPDF_DOCUMENT doc = ((Doc*)(intptr_t)handle)->doc;
    FPDF_PAGE page = FPDF_LoadPage(doc, index);
    if (!page) return NULL;
    FPDF_TEXTPAGE text = FPDFText_LoadPage(page);
    jstring s = NULL;
    if (text) {
        int count = FPDFText_CountChars(text);
        if (count < 0) count = 0;
        if (count > maxChars) count = maxChars;
        unsigned short* buf = malloc(((size_t)count + 1) * 2);
        if (buf) {
            for (int i = 0; i < count; i++) {
                unsigned int u = FPDFText_GetUnicode(text, i);
                buf[i] = (u == 0 || u > 0xFFFF) ? 0xFFFD : (unsigned short)u;
            }
            s = (*env)->NewString(env, (const jchar*)buf, count);
            free(buf);
        }
        FPDFText_ClosePage(text);
    }
    FPDF_ClosePage(page);
    return s;
}

// Boxes covering characters [start, start + count) of page [index], in the coordinates of a
// devW x devH drawing of the page (as rendered): [left, top, right, bottom, ...], at most 4096 boxes.
JNIEXPORT jfloatArray JNICALL Java_ua_hramota_pdf_PdfiumNative_rects(JNIEnv* env, jclass cls, jlong handle, jint index,
                                                                     jint start, jint count, jint devW, jint devH) {
    (void)cls;
    if (start < 0 || count <= 0 || devW <= 0 || devH <= 0) return (*env)->NewFloatArray(env, 0);
    FPDF_DOCUMENT doc = ((Doc*)(intptr_t)handle)->doc;
    FPDF_PAGE page = FPDF_LoadPage(doc, index);
    if (!page) return NULL;
    FPDF_TEXTPAGE text = FPDFText_LoadPage(page);
    jfloatArray out = NULL;
    if (text) {
        int n = FPDFText_CountRects(text, start, count);
        if (n < 0) n = 0;
        if (n > 4096) n = 4096;
        float* v = malloc(((size_t)n * 4 + 1) * sizeof(float));
        if (v) {
            int k = 0;
            for (int i = 0; i < n; i++) {
                double l, t, r, b;
                if (!FPDFText_GetRect(text, i, &l, &t, &r, &b)) continue;
                int x1, y1, x2, y2;
                FPDF_PageToDevice(page, 0, 0, devW, devH, 0, l, t, &x1, &y1);
                FPDF_PageToDevice(page, 0, 0, devW, devH, 0, r, b, &x2, &y2);
                v[k++] = (float)(x1 < x2 ? x1 : x2);
                v[k++] = (float)(y1 < y2 ? y1 : y2);
                v[k++] = (float)(x1 < x2 ? x2 : x1);
                v[k++] = (float)(y1 < y2 ? y2 : y1);
            }
            out = (*env)->NewFloatArray(env, k);
            if (out && k > 0) (*env)->SetFloatArrayRegion(env, out, 0, k, v);
            free(v);
        }
        FPDFText_ClosePage(text);
    }
    FPDF_ClosePage(page);
    return out;
}

// Index of the character at or near (x, y) of a devW x devH drawing of page [index]; -1 if none.
JNIEXPORT jint JNICALL Java_ua_hramota_pdf_PdfiumNative_charAt(JNIEnv* env, jclass cls, jlong handle, jint index,
                                                               jint x, jint y, jint devW, jint devH) {
    (void)env; (void)cls;
    if (devW <= 0 || devH <= 0) return -1;
    FPDF_DOCUMENT doc = ((Doc*)(intptr_t)handle)->doc;
    FPDF_PAGE page = FPDF_LoadPage(doc, index);
    if (!page) return -1;
    FPDF_TEXTPAGE text = FPDFText_LoadPage(page);
    int result = -1;
    if (text) {
        double px, py;
        if (FPDF_DeviceToPage(page, 0, 0, devW, devH, 0, x, y, &px, &py)) {
            // About a finger's width on the page
            result = FPDFText_GetCharIndexAtPos(text, px, py, 12.0, 12.0);
            if (result < 0) result = -1;
        }
        FPDFText_ClosePage(text);
    }
    FPDF_ClosePage(page);
    return result;
}
