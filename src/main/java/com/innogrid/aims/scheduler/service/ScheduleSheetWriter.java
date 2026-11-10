package com.innogrid.aims.scheduler.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.innogrid.aims.scheduler.entity.ScheduleRun;

/**
 * 스케줄러 화면 표를 xlsx 한 장으로 쓴다 — 외부 라이브러리 없이 OOXML 최소 부품 6개를 zip 으로 묶는다.
 *
 * <p>모든 셀은 인라인 문자열({@code t="inlineStr"})이다. 수식 셀을 만들지 않으므로 {@code =} · {@code +} · {@code @} 로
 * 시작하는 값도 글자로 남는다. XML 다섯 문자는 이스케이프하고, XML 1.0 이 허용하지 않는 제어 문자는 뺀다.</p>
 *
 * <p>셀 표기는 프론트 스케줄러 화면과 같다 — 값 없음은 {@code -}, 참 · 거짓은 예 · 아니오, 처리 상태는
 * Completed · Running · Failed(실행 전은 Pending), 시각은 스케줄러 시간대의 {@code yyyy-MM-dd HH:mm:ss}.</p>
 */
public final class ScheduleSheetWriter {

    /** xlsx 응답의 Content-Type. */
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 내보낸 파일 — 파일 이름(ASCII)과 본문. */
    public record Sheet(String filename, byte[] content) { }

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private ScheduleSheetWriter() { }

    /** 머리글 한 줄(굵게)과 행들을 시트 하나로 쓴다. */
    static Sheet write(String filename, String sheetName, List<String> header, List<List<String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", XML_HEAD
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", XML_HEAD
                    + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/workbook.xml", XML_HEAD
                    + "<workbook xmlns=\"" + MAIN_NS + "\" xmlns:r=\"" + REL_NS + "\">"
                    + "<sheets><sheet name=\"" + escape(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                    + "</workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", XML_HEAD
                    + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"" + REL_NS + "/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/styles.xml", XML_HEAD
                    + "<styleSheet xmlns=\"" + MAIN_NS + "\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>");
            StringBuilder sheet = new StringBuilder(XML_HEAD).append("<worksheet xmlns=\"").append(MAIN_NS).append("\"><sheetData>");
            appendRow(sheet, 1, header, true);
            for (int i = 0; i < rows.size(); i++) {
                appendRow(sheet, i + 2, rows.get(i), false);
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("xlsx 작성 실패", e);
        }
        return new Sheet(filename, out.toByteArray());
    }

    // ── 화면 표기 ─────────────────────────────────────────────

    /** 값 없음(null · 공백뿐)은 {@code -}, 나머지는 앞뒤 공백을 지운 값. */
    static String text(String value) {
        if (value == null) return "-";
        String trimmed = value.strip();
        return trimmed.isEmpty() ? "-" : trimmed;
    }

    static String yesNo(boolean value) {
        return value ? "예" : "아니오";
    }

    /** 처리 상태 — 실행 전(null)은 Pending. */
    static String status(ScheduleRun.RunStatus status) {
        if (status == null) return "Pending";
        return switch (status) {
            case COMPLETED -> "Completed";
            case RUNNING -> "Running";
            case FAILED -> "Failed";
        };
    }

    /** 시각 — 스케줄러 시간대의 {@code yyyy-MM-dd HH:mm:ss}, 없으면 {@code -}. */
    static String time(OffsetDateTime value, ZoneId zone) {
        return value == null ? "-" : value.atZoneSameInstant(zone).format(LOCAL_TIME);
    }

    // ── OOXML ─────────────────────────────────────────────────

    private static void appendRow(StringBuilder sb, int rowNumber, List<String> cells, boolean bold) {
        sb.append("<row r=\"").append(rowNumber).append("\">");
        for (int col = 0; col < cells.size(); col++) {
            sb.append("<c r=\"").append(columnName(col)).append(rowNumber).append("\" t=\"inlineStr\"");
            if (bold) sb.append(" s=\"1\"");
            sb.append("><is><t xml:space=\"preserve\">").append(escape(cells.get(col))).append("</t></is></c>");
        }
        sb.append("</row>");
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            name.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return name.toString();
    }

    /** XML 다섯 문자를 이스케이프하고 XML 1.0 이 허용하지 않는 문자(대부분의 제어 문자)는 뺀다. */
    static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    if (allowedInXml(cp)) sb.appendCodePoint(cp);
                }
            }
        });
        return sb.toString();
    }

    private static boolean allowedInXml(int cp) {
        return cp == 0x9 || cp == 0xA || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
