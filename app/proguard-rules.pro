# PdfBox-Android's JPX image decoder is optional. Text extraction does not render PDF images;
# page rasterization uses Android PdfRenderer. Do not retain or require the optional JP2 library.
-dontwarn com.gemalto.jp2.JP2Decoder
