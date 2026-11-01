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
