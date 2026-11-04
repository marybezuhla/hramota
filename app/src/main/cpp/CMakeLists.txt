cmake_minimum_required(VERSION 3.22.1)
project(hramota_pdf C)

# PDFium: the prebuilt library from pdfium-binaries (see /pdfium/ATTESTATION.txt), no JavaScript engine
set(PDFIUM_DIR ${CMAKE_SOURCE_DIR}/../../../../pdfium)
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES IMPORTED_LOCATION ${PDFIUM_DIR}/lib/${ANDROID_ABI}/libpdfium.so)

add_library(hramota_pdf SHARED pdf_engine.c)
target_include_directories(hramota_pdf PRIVATE ${PDFIUM_DIR}/include)
target_compile_options(hramota_pdf PRIVATE -Wall -Wextra -Werror -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2)
target_link_options(hramota_pdf PRIVATE -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384)
target_link_libraries(hramota_pdf pdfium log)

# eSpeak NG for the English voice's phonemes: built from source at a fixed release (see /espeak/VERSION), static
set(ESPEAK_DIR ${CMAKE_SOURCE_DIR}/../../../../espeak)
file(GLOB ESPEAK_LIBS ${ESPEAK_DIR}/lib/${ANDROID_ABI}/*.a)
add_library(hramota_speech SHARED espeak_jni.c)
target_include_directories(hramota_speech PRIVATE ${ESPEAK_DIR}/include)
target_compile_options(hramota_speech PRIVATE -Wall -Wextra -Werror -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2)
# (eSpeak's own functions are not exported: only the two JNI calls are)
target_link_options(hramota_speech PRIVATE -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL)
# (the libraries in a group: eSpeak's own parts refer to each other)
target_link_libraries(hramota_speech -Wl,--start-group ${ESPEAK_LIBS} -Wl,--end-group log)
