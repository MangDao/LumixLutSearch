package io.github.lumixlut.edit.lut;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * .cube 解析器。只认 3D LUT（31³ / 33³ / 65³ 都行），1D LUT 直接抛异常说明原因。
 * 这是行业通用文本格式，Adafruit/Adobe/Resolve 导出的都能吃。
 */
public final class CubeParser {

    private CubeParser() {
    }

    public static LutData parse(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 1 << 16)) {
            return parse(reader, file.getName());
        }
    }

    public static LutData parse(InputStream in, String fallbackTitle) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            return parse(reader, fallbackTitle);
        }
    }

    public static LutData parse(BufferedReader reader, String fallbackTitle) throws IOException {
        int size = 0;
        float[] domainMin = {0f, 0f, 0f};
        float[] domainMax = {1f, 1f, 1f};
        String title = fallbackTitle;

        float[] data = null;
        int cursor = 0;
        String line;
        int lineNo = 0;
        String targetStyle = null;

        while ((line = reader.readLine()) != null) {
            lineNo++;
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '#') {
                // #LUMIXPHOTOSTYLE STD —— 这条 LUT 期望的输入素材格调
                String upperComment = line.toUpperCase(Locale.US);
                if (upperComment.startsWith("#LUMIXPHOTOSTYLE")) {
                    String style = line.substring("#LUMIXPHOTOSTYLE".length()).trim();
                    if (!style.isEmpty()) {
                        targetStyle = style.toUpperCase(Locale.US);
                    }
                }
                continue;
            }
            String upper = line.toUpperCase(Locale.US);

            if (upper.startsWith("TITLE")) {
                int first = line.indexOf('"');
                int last = line.lastIndexOf('"');
                if (first >= 0 && last > first) {
                    title = line.substring(first + 1, last);
                }
                continue;
            }
            if (upper.startsWith("LUT_3D_SIZE")) {
                size = parseIntField(line);
                if (size < 2 || size > 256) {
                    throw new IOException("LUT_3D_SIZE 不合理：" + size + "（第 " + lineNo + " 行）");
                }
                data = new float[size * size * size * 3];
                continue;
            }
            if (upper.startsWith("LUT_1D_SIZE")) {
                throw new IOException("这是 1D LUT，不支持。只吃 3D LUT（第 " + lineNo + " 行）");
            }
            if (upper.startsWith("DOMAIN_MIN")) {
                float[] v = parseTriple(line, 3);
                System.arraycopy(v, 0, domainMin, 0, 3);
                continue;
            }
            if (upper.startsWith("DOMAIN_MAX")) {
                float[] v = parseTriple(line, 3);
                System.arraycopy(v, 0, domainMax, 0, 3);
                continue;
            }
            if (upper.startsWith("LUT_3D_INPUT_RANGE")) {
                float[] v = parseTriple(line, 2);
                if (v.length == 2) {
                    domainMin = new float[]{v[0], v[0], v[0]};
                    domainMax = new float[]{v[1], v[1], v[1]};
                }
                continue;
            }

            // 数据行：没有前缀，直接从第 0 个字段读
            if (data == null) {
                throw new IOException("数据行出现在 LUT_3D_SIZE 之前（第 " + lineNo + " 行）");
            }
            float[] rgb = parseDataRow(line);
            if (rgb == null) {
                continue;
            }
            if (cursor + 3 > data.length) {
                throw new IOException("数据行数超过 " + size + "³（第 " + lineNo + " 行）");
            }
            data[cursor++] = clamp01(rgb[0]);
            data[cursor++] = clamp01(rgb[1]);
            data[cursor++] = clamp01(rgb[2]);
        }

        if (size == 0 || data == null) {
            throw new IOException("文件里找不到 LUT_3D_SIZE，可能不是 .cube");
        }
        int expected = size * size * size * 3;
        if (cursor != expected) {
            throw new IOException("数据不完整：应有 " + (expected / 3) + " 行，实际 "
                    + (cursor / 3) + " 行");
        }

        float[] range = new float[3];
        for (int i = 0; i < 3; i++) {
            range[i] = domainMax[i] - domainMin[i];
            if (range[i] <= 0f) {
                range[i] = 1f;
            }
        }
        return new LutData(size, data, domainMin, range, title, targetStyle);
    }

    private static int parseIntField(String line) throws IOException {
        String[] parts = line.split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            try {
                return Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                // 继续往后找数字
            }
        }
        throw new IOException("解析不出数字：" + line);
    }

    private static float[] parseTriple(String line, int count) throws IOException {
        String[] parts = line.split("\\s+");
        float[] out = new float[count];
        int filled = 0;
        for (int i = 1; i < parts.length && filled < count; i++) {
            try {
                out[filled++] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException ignored) {
                // 跳过非数字
            }
        }
        if (filled < count) {
            throw new IOException("字段数不够，需要 " + count + " 个：" + line);
        }
        return out;
    }

    /**
     * 数据行：Cube 里就是裸的三列，没有键名。
     * 和上面那个带键名的解析分开写 —— 之前合并成一个，首列被当成键名吞掉了。
     */
    private static float[] parseDataRow(String line) {
        String[] parts = line.split("\\s+");
        float[] out = new float[3];
        int filled = 0;
        for (int i = 0; i < parts.length && filled < 3; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            try {
                out[filled++] = Float.parseFloat(parts[i]);
            } catch (NumberFormatException ignored) {
                // 不是数据行（比如 TITLE 之类没被识别的键），放弃这一行
                return null;
            }
        }
        return filled == 3 ? out : null;
    }

    private static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }
}
