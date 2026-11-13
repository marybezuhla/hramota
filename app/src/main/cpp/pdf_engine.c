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
#include "fpdf_annot.h"
#include "fpdf_save.h"
#include "fpdf_edit.h"

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


// ---- Saving notes into the PDF ----

typedef struct {
    FPDF_FILEWRITE base;
    int fd;
    int ok;
} FdWriter;

static int write_block(FPDF_FILEWRITE* w, const void* data, unsigned long size) {
    FdWriter* f = (FdWriter*)w;
    const unsigned char* p = (const unsigned char*)data;
    unsigned long done = 0;
    while (done < size) {
        ssize_t n = write(f->fd, p + done, size - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { f->ok = 0; return 0; }
        done += (unsigned long)n;
    }
    return 1;
}

// Writes a copy of the document with highlight annotations for the notes, to outFd. Each note is
// pages[i], starts[i], counts[i] (characters on the page), colors[i] (0xAARRGGBB) and texts[i]
// (its words, or null). The document being shown is not changed: a second copy is loaded and edited.
JNIEXPORT jboolean JNICALL Java_ua_hramota_pdf_PdfiumNative_saveNotes(JNIEnv* env, jclass cls, jlong handle, jint outFd,
                                                                    jintArray pages, jintArray starts, jintArray counts,
                                                                    jintArray colors, jobjectArray texts) {
    (void)cls;
    Doc* d = (Doc*)(intptr_t)handle;
    if (!d || outFd < 0) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, pages);
    if (n < 0 || n > 5000 || (*env)->GetArrayLength(env, starts) != n || (*env)->GetArrayLength(env, counts) != n ||
        (*env)->GetArrayLength(env, colors) != n || (*env)->GetArrayLength(env, texts) != n) return JNI_FALSE;
    FPDF_DOCUMENT doc = FPDF_LoadCustomDocument(&d->access, NULL);
    if (!doc) return JNI_FALSE;
    jint* pg = (*env)->GetIntArrayElements(env, pages, NULL);
    jint* st = (*env)->GetIntArrayElements(env, starts, NULL);
    jint* ct = (*env)->GetIntArrayElements(env, counts, NULL);
    jint* co = (*env)->GetIntArrayElements(env, colors, NULL);
    if (!pg || !st || !ct || !co) {
        if (pg) (*env)->ReleaseIntArrayElements(env, pages, pg, JNI_ABORT);
        if (st) (*env)->ReleaseIntArrayElements(env, starts, st, JNI_ABORT);
        if (ct) (*env)->ReleaseIntArrayElements(env, counts, ct, JNI_ABORT);
        if (co) (*env)->ReleaseIntArrayElements(env, colors, co, JNI_ABORT);
        FPDF_CloseDocument(doc);
        return JNI_FALSE;
    }
    int pageCount = FPDF_GetPageCount(doc);
    for (jsize i = 0; i < n; i++) {
        if (pg[i] < 0 || pg[i] >= pageCount || st[i] < 0 || ct[i] <= 0) continue;
        FPDF_PAGE page = FPDF_LoadPage(doc, pg[i]);
        if (!page) continue;
        FPDF_TEXTPAGE text = FPDFText_LoadPage(page);
        if (text) {
            int rects = FPDFText_CountRects(text, st[i], ct[i]);
            if (rects > 0 && rects <= 4096) {
                FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_HIGHLIGHT);
                if (annot) {
                    unsigned int c = (unsigned int)co[i];
                    FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF);
                    double minL = 1e9, minB = 1e9, maxR = -1e9, maxT = -1e9;
                    int got = 0;
                    for (int r = 0; r < rects; r++) {
                        double l, t, rr, b;
                        if (!FPDFText_GetRect(text, r, &l, &t, &rr, &b)) continue;
                        got++;
                        FS_QUADPOINTSF q = { (float)l, (float)t, (float)rr, (float)t, (float)l, (float)b, (float)rr, (float)b };
                        FPDFAnnot_AppendAttachmentPoints(annot, &q);
                        if (l < minL) minL = l;
                        if (b < minB) minB = b;
                        if (rr > maxR) maxR = rr;
                        if (t > maxT) maxT = t;
                    }
                    // No box could be read: the annotation is taken off again
                    if (got == 0) { FPDFPage_CloseAnnot(annot); FPDFPage_RemoveAnnot(page, FPDFPage_GetAnnotCount(page) - 1); FPDFText_ClosePage(text); FPDF_ClosePage(page); continue; }
                    FS_RECTF box = { (float)minL, (float)maxT, (float)maxR, (float)minB };
                    FPDFAnnot_SetRect(annot, &box);
                    jstring words = (jstring)(*env)->GetObjectArrayElement(env, texts, i);
                    if (words) {
                        jsize len = (*env)->GetStringLength(env, words);
                        if (len > 0 && len <= 10000) {
                            unsigned short* w = malloc(((size_t)len + 1) * 2);
                            if (w) {
                                (*env)->GetStringRegion(env, words, 0, len, (jchar*)w);
                                w[len] = 0;
                                FPDFAnnot_SetStringValue(annot, "Contents", (FPDF_WIDESTRING)w);
                                free(w);
                            }
                        }
                        (*env)->DeleteLocalRef(env, words);
                    }
                    FPDFPage_CloseAnnot(annot);
                }
            }
            FPDFText_ClosePage(text);
        }
        FPDF_ClosePage(page);
    }
    (*env)->ReleaseIntArrayElements(env, pages, pg, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, starts, st, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, counts, ct, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, colors, co, JNI_ABORT);
    FdWriter w;
    memset(&w, 0, sizeof(w));
    w.base.version = 1;
    w.base.WriteBlock = write_block;
    w.fd = outFd;
    w.ok = 1;
    // The original kept as it is, the notes added after it (an incremental update)
    FPDF_BOOL saved = FPDF_SaveAsCopy(doc, &w.base, FPDF_INCREMENTAL);
    FPDF_CloseDocument(doc);
    return (saved && w.ok) ? JNI_TRUE : JNI_FALSE;
}

// How many annotations page [index] has (for tests).
JNIEXPORT jint JNICALL Java_ua_hramota_pdf_PdfiumNative_annotCount(JNIEnv* env, jclass cls, jlong handle, jint index) {
    (void)env; (void)cls;
    FPDF_PAGE page = FPDF_LoadPage(((Doc*)(intptr_t)handle)->doc, index);
    if (!page) return -1;
    int n = FPDFPage_GetAnnotCount(page);
    FPDF_ClosePage(page);
    return n;
}


// ---- Forms ----

// A form environment without JavaScript or XFA: fields are read and filled, nothing in the file runs.
static FPDF_FORMHANDLE form_env(FPDF_DOCUMENT doc, FPDF_FORMFILLINFO* info) {
    memset(info, 0, sizeof(*info));
    info->version = 1;
    return FPDFDOC_InitFormFillEnvironment(doc, info);
}

typedef unsigned long (*AnnotText)(FPDF_FORMHANDLE, FPDF_ANNOTATION, FPDF_WCHAR*, unsigned long);

static jstring annot_text(JNIEnv* env, FPDF_FORMHANDLE form, FPDF_ANNOTATION a, AnnotText get) {
    unsigned long n = get(form, a, NULL, 0);
    if (n <= 2 || n > 65536) return (*env)->NewStringUTF(env, "");
    unsigned short* buf = malloc(n);
    if (!buf) return (*env)->NewStringUTF(env, "");
    get(form, a, (FPDF_WCHAR*)buf, n);
    jstring s = utf16(env, buf, n);
    free(buf);
    return s;
}

static jstring option_label(JNIEnv* env, FPDF_FORMHANDLE form, FPDF_ANNOTATION a, int i) {
    unsigned long n = FPDFAnnot_GetOptionLabel(form, a, i, NULL, 0);
    if (n <= 2 || n > 4096) return (*env)->NewStringUTF(env, "");
    unsigned short* buf = malloc(n);
    if (!buf) return (*env)->NewStringUTF(env, "");
    FPDFAnnot_GetOptionLabel(form, a, i, (FPDF_WCHAR*)buf, n);
    jstring s = utf16(env, buf, n);
    free(buf);
    return s;
}

// The form's widgets, at most [max]: for each, 9 strings in a row: page, type, flags, name,
// alternate name (the label for people), value, checked ("1"/"0"), export value, and
// "left,top,right,bottom" on a devW x devH drawing of its page; then its options as one string
// joined by U+001F. Null when the document has no form.
JNIEXPORT jobjectArray JNICALL Java_ua_hramota_pdf_PdfiumNative_fields(JNIEnv* env, jclass cls, jlong handle, jint max, jint devW, jint devH) {
    (void)cls;
    FPDF_DOCUMENT doc = ((Doc*)(intptr_t)handle)->doc;
    if (FPDF_GetFormType(doc) == FORMTYPE_NONE) return NULL;
    FPDF_FORMFILLINFO info;
    FPDF_FORMHANDLE form = form_env(doc, &info);
    if (!form) return NULL;
    jclass str = (*env)->FindClass(env, "java/lang/String");
    const int per = 10;
    jobjectArray out = (*env)->NewObjectArray(env, max * per, str, NULL);
    int k = 0;
    int pages = FPDF_GetPageCount(doc);
    for (int p = 0; p < pages && k < max; p++) {
        FPDF_PAGE page = FPDF_LoadPage(doc, p);
        if (!page) continue;
        FORM_OnAfterLoadPage(page, form);
        int n = FPDFPage_GetAnnotCount(page);
        for (int i = 0; i < n && k < max; i++) {
            FPDF_ANNOTATION a = FPDFPage_GetAnnot(page, i);
            if (!a) continue;
            if (FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_WIDGET) {
                int type = FPDFAnnot_GetFormFieldType(form, a);
                FS_RECTF r;
                char buf[160];
                int x1 = 0, y1 = 0, x2 = 0, y2 = 0;
                if (FPDFAnnot_GetRect(a, &r)) {
                    FPDF_PageToDevice(page, 0, 0, devW, devH, 0, r.left, r.top, &x1, &y1);
                    FPDF_PageToDevice(page, 0, 0, devW, devH, 0, r.right, r.bottom, &x2, &y2);
                }
                int base = k * per;
                snprintf(buf, sizeof(buf), "%d", p);
                (*env)->SetObjectArrayElement(env, out, base, (*env)->NewStringUTF(env, buf));
                snprintf(buf, sizeof(buf), "%d", type);
                (*env)->SetObjectArrayElement(env, out, base + 1, (*env)->NewStringUTF(env, buf));
                snprintf(buf, sizeof(buf), "%d", FPDFAnnot_GetFormFieldFlags(form, a));
                (*env)->SetObjectArrayElement(env, out, base + 2, (*env)->NewStringUTF(env, buf));
                jstring s;
                s = annot_text(env, form, a, FPDFAnnot_GetFormFieldName); (*env)->SetObjectArrayElement(env, out, base + 3, s); (*env)->DeleteLocalRef(env, s);
                s = annot_text(env, form, a, FPDFAnnot_GetFormFieldAlternateName); (*env)->SetObjectArrayElement(env, out, base + 4, s); (*env)->DeleteLocalRef(env, s);
                s = annot_text(env, form, a, FPDFAnnot_GetFormFieldValue); (*env)->SetObjectArrayElement(env, out, base + 5, s); (*env)->DeleteLocalRef(env, s);
                (*env)->SetObjectArrayElement(env, out, base + 6, (*env)->NewStringUTF(env, FPDFAnnot_IsChecked(form, a) ? "1" : "0"));
                s = annot_text(env, form, a, FPDFAnnot_GetFormFieldExportValue); (*env)->SetObjectArrayElement(env, out, base + 7, s); (*env)->DeleteLocalRef(env, s);
                snprintf(buf, sizeof(buf), "%d,%d,%d,%d", x1 < x2 ? x1 : x2, y1 < y2 ? y1 : y2, x1 < x2 ? x2 : x1, y1 < y2 ? y2 : y1);
                (*env)->SetObjectArrayElement(env, out, base + 8, (*env)->NewStringUTF(env, buf));
                // The choices of a list or a drop-down, joined
                int options = (type == FPDF_FORMFIELD_COMBOBOX || type == FPDF_FORMFIELD_LISTBOX) ? FPDFAnnot_GetOptionCount(form, a) : 0;
                if (options > 500) options = 500;
                jstring joined = (*env)->NewStringUTF(env, "");
                for (int o = 0; o < options; o++) {
                    jstring label = option_label(env, form, a, o);
                    jclass sc = (*env)->GetObjectClass(env, joined);
                    jmethodID concat = (*env)->GetMethodID(env, sc, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
                    jstring sep = (*env)->NewStringUTF(env, o == 0 ? "" : "\x1f");
                    jstring a1 = (jstring)(*env)->CallObjectMethod(env, joined, concat, sep);
                    jstring a2 = (jstring)(*env)->CallObjectMethod(env, a1, concat, label);
                    (*env)->DeleteLocalRef(env, joined); (*env)->DeleteLocalRef(env, a1); (*env)->DeleteLocalRef(env, sep); (*env)->DeleteLocalRef(env, label); (*env)->DeleteLocalRef(env, sc);
                    joined = a2;
                }
                (*env)->SetObjectArrayElement(env, out, base + 9, joined);
                (*env)->DeleteLocalRef(env, joined);
                k++;
            }
            FPDFPage_CloseAnnot(a);
        }
        FORM_OnBeforeClosePage(page, form);
        FPDF_ClosePage(page);
    }
    FPDFDOC_ExitFormFillEnvironment(form);
    // Only the rows filled
    jobjectArray trimmed = (*env)->NewObjectArray(env, k * per, str, NULL);
    for (int i = 0; i < k * per; i++) {
        jobject o = (*env)->GetObjectArrayElement(env, out, i);
        (*env)->SetObjectArrayElement(env, trimmed, i, o);
        if (o) (*env)->DeleteLocalRef(env, o);
    }
    return trimmed;
}

static int same_text(JNIEnv* env, jstring a, FPDF_FORMHANDLE form, FPDF_ANNOTATION an, AnnotText get) {
    jstring b = annot_text(env, form, an, get);
    const jchar* ca = (*env)->GetStringChars(env, a, NULL);
    const jchar* cb = (*env)->GetStringChars(env, b, NULL);
    jsize la = (*env)->GetStringLength(env, a), lb = (*env)->GetStringLength(env, b);
    int same = la == lb && memcmp(ca, cb, (size_t)la * 2) == 0;
    (*env)->ReleaseStringChars(env, a, ca);
    (*env)->ReleaseStringChars(env, b, cb);
    (*env)->DeleteLocalRef(env, b);
    return same;
}

static FPDF_WIDESTRING wide(JNIEnv* env, jstring s) {
    jsize len = (*env)->GetStringLength(env, s);
    unsigned short* w = malloc(((size_t)len + 1) * 2);
    if (!w) return NULL;
    (*env)->GetStringRegion(env, s, 0, len, (jchar*)w);
    w[len] = 0;
    return (FPDF_WIDESTRING)w;
}

// Writes a filled copy to outFd: names[i] gets values[i] (text; for a check box "1"/"0"; for radio
// buttons the export value of the one chosen; for lists the option's label). With [sigPixels]
// (RGBA, sigW x sigH) a picture of a hand-drawn signature is put on page [sigPage] at sigRect
// (left, top, right, bottom on a devW x devH drawing of the page). The original stays as it is: the
// changes are added after it (an incremental update).
JNIEXPORT jboolean JNICALL Java_ua_hramota_pdf_PdfiumNative_saveForm(JNIEnv* env, jclass cls, jlong handle, jint outFd,
                                                                   jobjectArray names, jobjectArray values,
                                                                   jint sigPage, jintArray sigRect, jobject sigPixels, jint sigW, jint sigH, jint devW, jint devH) {
    (void)cls;
    Doc* d = (Doc*)(intptr_t)handle;
    if (!d || outFd < 0 || devW <= 0 || devH <= 0) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, names);
    if (n < 0 || n > 5000 || (*env)->GetArrayLength(env, values) != n) return JNI_FALSE;
    FPDF_DOCUMENT doc = FPDF_LoadCustomDocument(&d->access, NULL);
    if (!doc) return JNI_FALSE;
    int ok = 1;
    if (n > 0) {
        FPDF_FORMFILLINFO info;
        FPDF_FORMHANDLE form = form_env(doc, &info);
        if (!form) { FPDF_CloseDocument(doc); return JNI_FALSE; }
        int pages = FPDF_GetPageCount(doc);
        for (int p = 0; p < pages; p++) {
            FPDF_PAGE page = FPDF_LoadPage(doc, p);
            if (!page) continue;
            FORM_OnAfterLoadPage(page, form);
            int count = FPDFPage_GetAnnotCount(page);
            for (int i = 0; i < count; i++) {
                FPDF_ANNOTATION a = FPDFPage_GetAnnot(page, i);
                if (!a) continue;
                if (FPDFAnnot_GetSubtype(a) != FPDF_ANNOT_WIDGET || (FPDFAnnot_GetFormFieldFlags(form, a) & FPDF_FORMFLAG_READONLY)) { FPDFPage_CloseAnnot(a); continue; }
                for (jsize j = 0; j < n; j++) {
                    jstring name = (jstring)(*env)->GetObjectArrayElement(env, names, j);
                    if (!name || !same_text(env, name, form, a, FPDFAnnot_GetFormFieldName)) { if (name) (*env)->DeleteLocalRef(env, name); continue; }
                    jstring value = (jstring)(*env)->GetObjectArrayElement(env, values, j);
                    int type = FPDFAnnot_GetFormFieldType(form, a);
                    FS_RECTF r;
                    int haveRect = FPDFAnnot_GetRect(a, &r);
                    double cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
                    if (value) {
                        if (type == FPDF_FORMFIELD_TEXTFIELD || (type == FPDF_FORMFIELD_COMBOBOX && !same_text(env, value, form, a, FPDFAnnot_GetFormFieldValue))) {
                            if (FORM_SetFocusedAnnot(form, a)) {
                                if (type == FPDF_FORMFIELD_COMBOBOX) {
                                    int options = FPDFAnnot_GetOptionCount(form, a);
                                    int chosen = -1;
                                    for (int o = 0; o < options && o < 500 && chosen < 0; o++) {
                                        jstring label = option_label(env, form, a, o);
                                        const jchar* c1 = (*env)->GetStringChars(env, label, NULL);
                                        const jchar* c2 = (*env)->GetStringChars(env, value, NULL);
                                        jsize l1 = (*env)->GetStringLength(env, label), l2 = (*env)->GetStringLength(env, value);
                                        if (l1 == l2 && memcmp(c1, c2, (size_t)l1 * 2) == 0) chosen = o;
                                        (*env)->ReleaseStringChars(env, label, c1); (*env)->ReleaseStringChars(env, value, c2);
                                        (*env)->DeleteLocalRef(env, label);
                                    }
                                    if (chosen >= 0) FORM_SetIndexSelected(form, page, chosen, 1);
                                    else { FORM_SelectAllText(form, page); FPDF_WIDESTRING w = wide(env, value); if (w) { FORM_ReplaceSelection(form, page, w); free((void*)w); } }
                                } else {
                                    FORM_SelectAllText(form, page);
                                    FPDF_WIDESTRING w = wide(env, value);
                                    if (w) { FORM_ReplaceSelection(form, page, w); free((void*)w); }
                                }
                                FORM_ForceToKillFocus(form);
                            }
                        } else if (type == FPDF_FORMFIELD_LISTBOX) {
                            if (FORM_SetFocusedAnnot(form, a)) {
                                int options = FPDFAnnot_GetOptionCount(form, a);
                                for (int o = 0; o < options && o < 500; o++) {
                                    jstring label = option_label(env, form, a, o);
                                    const jchar* c1 = (*env)->GetStringChars(env, label, NULL);
                                    const jchar* c2 = (*env)->GetStringChars(env, value, NULL);
                                    jsize l1 = (*env)->GetStringLength(env, label), l2 = (*env)->GetStringLength(env, value);
                                    int match = l1 == l2 && memcmp(c1, c2, (size_t)l1 * 2) == 0;
                                    (*env)->ReleaseStringChars(env, label, c1); (*env)->ReleaseStringChars(env, value, c2);
                                    (*env)->DeleteLocalRef(env, label);
                                    if (match) { FORM_SetIndexSelected(form, page, o, 1); break; }
                                }
                                FORM_ForceToKillFocus(form);
                            }
                        } else if ((type == FPDF_FORMFIELD_CHECKBOX || type == FPDF_FORMFIELD_RADIOBUTTON) && haveRect) {
                            const char* v = (*env)->GetStringUTFChars(env, value, NULL);
                            int want;
                            if (type == FPDF_FORMFIELD_CHECKBOX) want = v && v[0] == '1';
                            // A radio button: on if its export value is the one chosen
                            else want = same_text(env, value, form, a, FPDFAnnot_GetFormFieldExportValue);
                            if (v) (*env)->ReleaseStringUTFChars(env, value, v);
                            int now = FPDFAnnot_IsChecked(form, a) ? 1 : 0;
                            // A radio button is never turned off by tapping it; another one is turned on instead
                            if (want != now && (type == FPDF_FORMFIELD_CHECKBOX || want)) {
                                FORM_OnLButtonDown(form, page, 0, cx, cy);
                                FORM_OnLButtonUp(form, page, 0, cx, cy);
                                FORM_ForceToKillFocus(form);
                            }
                        }
                        (*env)->DeleteLocalRef(env, value);
                    }
                    (*env)->DeleteLocalRef(env, name);
                    break;
                }
                FPDFPage_CloseAnnot(a);
            }
            FORM_OnBeforeClosePage(page, form);
            FPDF_ClosePage(page);
        }
        FPDFDOC_ExitFormFillEnvironment(form);
    }
    // The signature, as a picture on the page
    if (sigPixels && sigPage >= 0 && sigW > 0 && sigH > 0 && sigW <= 4096 && sigH <= 4096 && (*env)->GetArrayLength(env, sigRect) == 4) {
        void* pixels = (*env)->GetDirectBufferAddress(env, sigPixels);
        jlong cap = (*env)->GetDirectBufferCapacity(env, sigPixels);
        jint* rc = (*env)->GetIntArrayElements(env, sigRect, NULL);
        FPDF_PAGE page = (pixels && cap >= (jlong)sigW * sigH * 4 && rc) ? FPDF_LoadPage(doc, sigPage) : NULL;
        if (page) {
            double l, t, r, b;
            FPDF_DeviceToPage(page, 0, 0, devW, devH, 0, rc[0], rc[1], &l, &t);
            FPDF_DeviceToPage(page, 0, 0, devW, devH, 0, rc[2], rc[3], &r, &b);
            double left = l < r ? l : r, bottom = t < b ? t : b, width = l < r ? r - l : l - r, height = t < b ? b - t : t - b;
            FPDF_PAGEOBJECT img = FPDFPageObj_NewImageObj(doc);
            FPDF_BITMAP bitmap = FPDFBitmap_CreateEx(sigW, sigH, FPDFBitmap_BGRA, pixels, sigW * 4);
            if (img && bitmap && width > 0 && height > 0 && FPDFImageObj_SetBitmap(&page, 1, img, bitmap)) {
                FPDFImageObj_SetMatrix(img, width, 0, 0, height, left, bottom);
                FPDFPage_InsertObject(page, img);
                if (!FPDFPage_GenerateContent(page)) ok = 0;
            } else {
                ok = 0;
                if (img) FPDFPageObj_Destroy(img);
            }
            if (bitmap) FPDFBitmap_Destroy(bitmap);
            FPDF_ClosePage(page);
        } else ok = 0;
        if (rc) (*env)->ReleaseIntArrayElements(env, sigRect, rc, JNI_ABORT);
    }
    FdWriter w;
    memset(&w, 0, sizeof(w));
    w.base.version = 1;
    w.base.WriteBlock = write_block;
    w.fd = outFd;
    w.ok = 1;
    FPDF_BOOL saved = ok && FPDF_SaveAsCopy(doc, &w.base, FPDF_INCREMENTAL);
    FPDF_CloseDocument(doc);
    return (saved && w.ok) ? JNI_TRUE : JNI_FALSE;
}
