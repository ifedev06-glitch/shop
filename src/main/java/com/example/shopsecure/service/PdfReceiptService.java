package com.example.shopsecure.service;

import com.example.shopsecure.dto.response.ReceiptResponse;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;

/**
 * Generates an 80mm-style thermal POS receipt as a PDF using PDFBox.
 *
 * Layout notes:
 * - Page width is fixed at 226pt (~80mm). Height is computed from actual
 *   content so there's no dead whitespace for short receipts.
 * - Item table + amounts use a monospace font (Courier) so columns line
 *   up exactly like a real POS printer, without per-string width math.
 * - Headings/centered lines (store name, receipt #, date, footer) still
 *   use measured centering since they're proportional fonts.
 */
@Service
public class PdfReceiptService {

    private static final float PAGE_WIDTH = 226f; // ~80mm
    private static final float MARGIN = 10f;

    // Monospace column layout (Courier: char width = size * 0.6)
    private static final float ITEM_FONT_SIZE = 8f;
    private static final float CHAR_WIDTH = ITEM_FONT_SIZE * 0.6f;
    private static final int COL_ITEM = 14;
    private static final int COL_QTY = 4;
    private static final int COL_PRICE = 10;
    private static final int COL_TOTAL = 10;

    public byte[] generateReceiptPdf(ReceiptResponse receipt) {
        int itemCount = receipt.getItems().size();

        // Estimate height purely from what will actually be drawn.
        float estimatedHeight = MARGIN * 2
                + 16   // store name line
                + 11   // receipt number
                + 13   // date
                + (hasText(receipt.getCustomerName()) ? 11 : 0)
                + 8    // rule + gap
                + 9    // table header
                + 8    // rule + gap
                + itemCount * 11f
                + 8    // rule + gap
                + 10   // subtotal
                + (hasDiscount(receipt) ? 10 : 0)
                + 14   // total
                + 12   // payment method
                + 8    // rule + gap
                + 10   // footer
                + 14;  // cut line

        float pageHeight = Math.max(estimatedHeight, 150); // sane floor only, not 340

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, pageHeight));
            doc.addPage(page);

            float pw = page.getMediaBox().getWidth();
            float cX = pw / 2;
            float y = page.getMediaBox().getHeight() - MARGIN;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                boolean hasLogo = false;

                if (hasText(receipt.getLogoUrl())) {
                    try {
                        var url = new URI(receipt.getLogoUrl()).toURL();
                        var img = ImageIO.read(url);
                        if (img != null) {
                            PDImageXObject pdImg = LosslessFactory.createFromImage(doc, img);
                            float logoSize = 22;
                            float scale = logoSize / Math.max(img.getWidth(), img.getHeight());
                            float dw = img.getWidth() * scale;
                            float dh = img.getHeight() * scale;

                            String name = truncate(receipt.getBusinessName(), 22);
                            PDType1Font bold = font(Standard14Fonts.FontName.HELVETICA_BOLD);

                            // Anchored to the left margin instead of centered as a unit.
                            float startX = MARGIN;

                            cs.drawImage(pdImg, startX, y - dh, dw, dh);

                            cs.setFont(bold, 11);
                            write(cs, startX + dw + 6, y - dh / 2 - 4, name);
                            y = y - dh - 6;
                            hasLogo = true;
                        }
                    } catch (Exception ignored) {
                        // fall through to text-only header
                    }
                }

                if (!hasLogo) {
                    PDType1Font bold = font(Standard14Fonts.FontName.HELVETICA_BOLD);
                    cs.setFont(bold, 11);
                    String name = receipt.getBusinessName();
                    write(cs, MARGIN, y, name);
                    y -= 16;
                }

                PDType1Font regular = font(Standard14Fonts.FontName.HELVETICA);
                cs.setFont(regular, 7);
                centerText(cs, regular, 7, cX, y, receipt.getReceiptNumber());
                y -= 11;

                String date = receipt.getCreatedAt().toLocalDate().toString();
                centerText(cs, regular, 7, cX, y, date);
                y -= 13;

                if (hasText(receipt.getCustomerName())) {
                    PDType1Font bold = font(Standard14Fonts.FontName.HELVETICA_BOLD);
                    cs.setFont(bold, 7);
                    write(cs, MARGIN, y, "Customer: " + receipt.getCustomerName());
                    y -= 11;
                }

                y = dashedLine(cs, MARGIN, y, pw - 2 * MARGIN);
                y -= 4;

                PDType1Font mono = font(Standard14Fonts.FontName.COURIER_BOLD);
                cs.setFont(mono, ITEM_FONT_SIZE);
                write(cs, MARGIN, y, monoRow("Item", "Qty", "Price", "Total"));
                y -= 9;

                y = dashedLine(cs, MARGIN, y, pw - 2 * MARGIN);
                y -= 4;

                PDType1Font monoReg = font(Standard14Fonts.FontName.COURIER);
                cs.setFont(monoReg, ITEM_FONT_SIZE);
                for (ReceiptResponse.Item item : receipt.getItems()) {
                    write(cs, MARGIN, y, monoRow(
                            truncate(item.getProductName(), COL_ITEM),
                            String.valueOf(item.getQuantity()),
                            fmt(item.getUnitPrice()),
                            fmt(item.getTotal())
                    ));
                    y -= 11;
                }

                y -= 3;
                y = dashedLine(cs, MARGIN, y, pw - 2 * MARGIN);
                y -= 6;

                float amountRightX = pw - MARGIN;

                PDType1Font h8 = font(Standard14Fonts.FontName.HELVETICA);
                cs.setFont(h8, 8);
                write(cs, MARGIN, y, "Subtotal:");
                writeRight(cs, h8, 8, amountRightX, y, "N" + fmt(receipt.getSubtotal()));
                y -= 10;

                if (hasDiscount(receipt)) {
                    write(cs, MARGIN, y, "Discount:");
                    writeRight(cs, h8, 8, amountRightX, y, "-N" + fmt(receipt.getDiscount()));
                    y -= 10;
                }

                PDType1Font hb10 = font(Standard14Fonts.FontName.HELVETICA_BOLD);
                cs.setFont(hb10, 10);
                write(cs, MARGIN, y, "TOTAL:");
                writeRight(cs, hb10, 10, amountRightX, y, "N" + fmt(receipt.getTotal()));
                y -= 14;

                PDType1Font hb7 = font(Standard14Fonts.FontName.HELVETICA_BOLD);
                cs.setFont(hb7, 7);
                centerText(cs, hb7, 7, cX, y, receipt.getPaymentMethod());
                y -= 12;

                y = dashedLine(cs, MARGIN, y, pw - 2 * MARGIN);
                y -= 10;

                PDType1Font h6 = font(Standard14Fonts.FontName.HELVETICA);
                cs.setFont(h6, 6);
                centerText(cs, h6, 6, cX, y, "Powered by ShopSecure");
                y -= 12;

                // Cut line — classic thermal-receipt visual cue
                cs.setFont(h6, 6);
                dashedLine(cs, MARGIN, y, pw - 2 * MARGIN);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF receipt", e);
        }
    }

    private PDType1Font font(Standard14Fonts.FontName name) {
        return new PDType1Font(name);
    }

    private float stringWidth(PDType1Font font, float size, String text) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (Exception e) {
            return 0;
        }
    }

    private void write(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private void centerText(PDPageContentStream cs, PDType1Font font, float size, float centerX, float y, String text) throws Exception {
        if (text == null) text = "";
        float tw = stringWidth(font, size, text);
        write(cs, centerX - tw / 2, y, text);
    }

    // Correct right-alignment: measures using the SAME font/size actually on the stream.
    private void writeRight(PDPageContentStream cs, PDType1Font font, float size, float rightX, float y, String text) throws Exception {
        float tw = stringWidth(font, size, text);
        write(cs, rightX - tw, y, text);
    }

    // Dashed rule instead of a solid line — reads more like a POS printout.
    private float dashedLine(PDPageContentStream cs, float x, float y, float w) throws Exception {
        cs.setLineWidth(0.6f);
        cs.setLineDashPattern(new float[]{2f, 2f}, 0);
        cs.moveTo(x, y);
        cs.lineTo(x + w, y);
        cs.stroke();
        cs.setLineDashPattern(new float[]{}, 0); // reset to solid for anything after
        return y - 4;
    }

    // Fixed-width monospace row: Item(14) Qty(4) Price(10) Total(10), right-aligned numeric cols.
    private String monoRow(String item, String qty, String price, String total) {
        return padRight(item, COL_ITEM) + " "
                + padRight(qty, COL_QTY) + " "
                + padLeft(price, COL_PRICE) + " "
                + padLeft(total, COL_TOTAL);
    }

    private String padRight(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(0, len);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private String padLeft(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(s.length() - len);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < len - s.length()) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String fmt(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,d", amount.longValue());
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private boolean hasDiscount(ReceiptResponse receipt) {
        return receipt.getDiscount() != null && receipt.getDiscount().compareTo(BigDecimal.ZERO) > 0;
    }
}