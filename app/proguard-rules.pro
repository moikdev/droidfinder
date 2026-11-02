# PdfBox-Android's JPX image decoder is optional. Text extraction does not render PDF images;
# page rasterization uses Android PdfRenderer. Do not retain or require the optional JP2 library.
-dontwarn com.gemalto.jp2.JP2Decoder

# Commons Compress exposes optional Zstandard/XZ readers through its general ZIP dispatcher.
# inspectZipOpen rejects every method except STORED (0) and DEFLATED (8) before any member
# stream is opened. These optional codecs are not shipped or advertised by Findroid.
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.XZInputStream
