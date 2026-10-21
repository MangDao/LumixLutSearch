package io.github.lumixlut.edit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 直接从 JPEG 字节里解松下 MakerNote，只为了拿一个字段：0x0089 PhotoStyle。
 *
 * 为什么不用 ExifInterface：它压根不暴露 MakerNote 原始字节，而 PhotoStyle
 * 是松下私有 tag。所以这里自己走一遍 JPEG 段 → TIFF IFD → MakerNote IFD。
 *
 * 字段值参考 ExifTool 的 Panasonic 标签表（0x0089 PhotoStyle），
 * 不在表里的值说明是相机新固件的枚举或用户自定义风格，一律报"自定义"。
 */
public final class PanasonicExif {

    private static final int TAG_MAKE = 0x010F;
    private static final int TAG_MODEL = 0x0110;
    private static final int TAG_EXIF_IFD = 0x8769;
    private static final int TAG_MAKER_NOTE = 0x927C;

    private static final int TAG_PHOTO_STYLE = 0x0089;
    private static final int TAG_HLG = 0x00D4;

    /** ExifTool 表里确认过的值；表外的都算自定义/新枚举 */
    private static final String[] STYLE_NAMES = new String[32];

    static {
        STYLE_NAMES[0] = "Auto";
        STYLE_NAMES[1] = "STD";
        STYLE_NAMES[2] = "VIVID";
        STYLE_NAMES[3] = "NAT";
        STYLE_NAMES[4] = "MONO";
        STYLE_NAMES[5] = "LAND";
        STYLE_NAMES[6] = "PORT";
        STYLE_NAMES[8] = "CINED";
        STYLE_NAMES[9] = "CINEV";
        STYLE_NAMES[11] = "L.MONO";
        STYLE_NAMES[12] = "709LIKE";
        STYLE_NAMES[15] = "L.MONO D";
        STYLE_NAMES[17] = "V-LOG";
        STYLE_NAMES[18] = "CINED2";
    }

    public static final class Info {
        public final String make;
        public final String model;
        /** 原始枚举值，读不到就是 null */
        public final Integer photoStyleRaw;
        public final String photoStyleName;
        public final boolean hlg;

        Info(String make, String model, Integer raw, String name, boolean hlg) {
            this.make = make;
            this.model = model;
            this.photoStyleRaw = raw;
            this.photoStyleName = name;
            this.hlg = hlg;
        }

        public boolean isVLog() {
            return photoStyleRaw != null && photoStyleRaw == 17;
        }

        public boolean isCustomStyle() {
            return photoStyleRaw != null && photoStyleName == null;
        }
    }

    private PanasonicExif() {
    }

    public static Info read(InputStream input) throws IOException {
        byte[] data = readUpTo(input, 512 * 1024);
        int tiff = findTiff(data);
        if (tiff < 0) {
            return null;
        }
        boolean little = data[tiff] == 'I';
        int ifd0 = readInt(data, tiff + 4, little);
        if (ifd0 <= 0) {
            return null;
        }

        String make = null;
        String model = null;
        int exifIfd = -1;
        int count = readShort(data, tiff + ifd0, little);
        for (int i = 0; i < count; i++) {
            int entry = tiff + ifd0 + 2 + i * 12;
            if (entry + 12 > data.length) {
                break;
            }
            int tag = readShort(data, entry, little);
            if (tag == TAG_MAKE) {
                make = readAscii(data, tiff, entry, little);
            } else if (tag == TAG_MODEL) {
                model = readAscii(data, tiff, entry, little);
            } else if (tag == TAG_EXIF_IFD) {
                exifIfd = readInt(data, entry + 8, little);
            }
        }
        if (exifIfd <= 0) {
            return new Info(make, model, null, null, false);
        }

        int makerOffset = -1;
        int makerLength = 0;
        count = readShort(data, tiff + exifIfd, little);
        for (int i = 0; i < count; i++) {
            int entry = tiff + exifIfd + 2 + i * 12;
            if (entry + 12 > data.length) {
                break;
            }
            int tag = readShort(data, entry, little);
            if (tag != TAG_MAKER_NOTE) {
                continue;
            }
            makerLength = readInt(data, entry + 4, little);
            if (makerLength <= 4) {
                makerOffset = entry + 8;
            } else {
                makerOffset = tiff + readInt(data, entry + 8, little);
            }
            break;
        }
        if (makerOffset < 0 || makerOffset + 12 > data.length) {
            return new Info(make, model, null, null, false);
        }

        // 松下 MakerNote 以 "Panasonic\0\0\0" 开头，之后是它自己的 IFD
        String magic = new String(data, makerOffset, 12, StandardCharsets.US_ASCII);
        if (!magic.startsWith("Panasonic")) {
            return new Info(make, model, null, null, false);
        }

        Integer styleRaw = null;
        boolean hlg = false;
        count = readShort(data, makerOffset + 12, little);
        if (count > 600) {
            count = 0;
        }
        for (int i = 0; i < count; i++) {
            int entry = makerOffset + 12 + 2 + i * 12;
            if (entry + 12 > data.length) {
                break;
            }
            int tag = readShort(data, entry, little);
            if (tag == TAG_PHOTO_STYLE) {
                styleRaw = readShort(data, entry + 8, little);
            } else if (tag == TAG_HLG) {
                hlg = readShort(data, entry + 8, little) != 0;
            }
        }

        String styleName = null;
        if (styleRaw != null && styleRaw >= 0 && styleRaw < STYLE_NAMES.length) {
            styleName = STYLE_NAMES[styleRaw];
        }
        return new Info(make, model, styleRaw, styleName, hlg);
    }

    private static byte[] readUpTo(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[limit];
        int total = 0;
        int read;
        while (total < limit && (read = in.read(buffer, total, limit - total)) > 0) {
            total += read;
        }
        byte[] result = new byte[total];
        System.arraycopy(buffer, 0, result, 0, total);
        return result;
    }

    /** 扫 JPEG 段，返回 APP1 里 TIFF 头的绝对偏移 */
    private static int findTiff(byte[] data) {
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return -1;
        }
        int i = 2;
        while (i < data.length - 4) {
            if ((data[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = data[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            if (marker == 0xDA) {
                return -1;
            }
            int length = readShort(data, i + 2, false);
            if (length < 2 || i + 2 + length > data.length) {
                return -1;
            }
            if (marker == 0xE1 && i + 10 < data.length
                    && data[i + 4] == 'E' && data[i + 5] == 'x' && data[i + 6] == 'i'
                    && data[i + 7] == 'f') {
                return i + 10;
            }
            i += 2 + length;
        }
        return -1;
    }

    private static int readShort(byte[] d, int off, boolean little) {
        if (off + 2 > d.length) {
            return 0;
        }
        int a = d[off] & 0xFF;
        int b = d[off + 1] & 0xFF;
        return little ? (b << 8 | a) : (a << 8 | b);
    }

    private static int readInt(byte[] d, int off, boolean little) {
        if (off + 4 > d.length) {
            return 0;
        }
        int a = d[off] & 0xFF;
        int b = d[off + 1] & 0xFF;
        int c = d[off + 2] & 0xFF;
        int e = d[off + 3] & 0xFF;
        return little ? (e << 24 | c << 16 | b << 8 | a) : (a << 24 | b << 16 | c << 8 | e);
    }

    private static String readAscii(byte[] d, int tiff, int entry, boolean little) {
        int len = readInt(d, entry + 4, little);
        if (len <= 0 || len > 256) {
            return null;
        }
        int off = len <= 4 ? entry + 8 : tiff + readInt(d, entry + 8, little);
        if (off < 0 || off + len > d.length) {
            return null;
        }
        int end = off;
        while (end < off + len && d[end] != 0) {
            end++;
        }
        return new String(d, off, end - off, StandardCharsets.US_ASCII).trim();
    }
}
