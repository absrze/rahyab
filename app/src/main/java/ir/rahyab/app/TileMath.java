package ir.rahyab.app;

/** محاسبه کاشی‌های تصویر ماهواره‌ای؛ بدون وابستگی به اندروید. */
final class TileMath {
    private TileMath() { }

    /** زوم کاشی تصویر که روی این صفحه تیزترین نتیجه را می‌دهد. */
    static int sourceZoom(int mapZoom, int mapTileSize, int imageSize, int min, int max) {
        int extra = (int) Math.round(Math.log((double) mapTileSize / imageSize) / Math.log(2));
        return Math.max(min, Math.min(max, mapZoom + extra));
    }

    /** اندازه یک کاشی تصویر روی صفحه (پیکسل). */
    static double screenSize(int mapZoom, int mapTileSize, int sourceZoom) {
        return mapTileSize * Math.pow(2, mapZoom - sourceZoom);
    }

    /** ردیف در فایل MBTiles (شمارش از پایین). */
    static int tmsRow(int zoom, int y) {
        return (1 << zoom) - 1 - y;
    }
}
