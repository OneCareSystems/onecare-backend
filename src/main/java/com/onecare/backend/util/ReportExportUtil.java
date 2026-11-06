package com.onecare.backend.util;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Renders a generic tabular result set as CSV or PDF for report export
 * (FR039/FR040, AC5). Both renderings consume the exact same headers/rows the
 * on-screen JSON is built from, so exported data always matches the screen.
 */
public final class ReportExportUtil {

    private ReportExportUtil() {
    }

    /**
     * Renders the table as RFC-4180 style CSV (UTF-8 with BOM for Excel,
     * CRLF line endings, quoted fields where needed).
     */
    public static byte[] toCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF'); // UTF-8 BOM so Excel opens it correctly
        appendCsvLine(csv, headers);
        for (List<String> row : rows) {
            appendCsvLine(csv, row);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Renders the table as a single-page-wide landscape PDF (OpenPDF).
     */
    public static byte[] toPdf(String title, List<String> headers, List<List<String>> rows) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
            document.add(new Paragraph(new Phrase(title, titleFont)));
            document.add(new Paragraph(" "));

            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 7);
            PdfPTable table = new PdfPTable(headers.size());
            table.setWidthPercentage(100);

            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7);
            for (String header : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
                cell.setBackgroundColor(Color.LIGHT_GRAY);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                table.addCell(cell);
            }
            for (List<String> row : rows) {
                for (String value : row) {
                    table.addCell(new Phrase(value == null ? "" : value, cellFont));
                }
            }
            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("Failed to render report PDF", e);
        }
    }

    private static void appendCsvLine(StringBuilder csv, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escapeCsv(fields.get(i)));
        }
        csv.append("\r\n");
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}
