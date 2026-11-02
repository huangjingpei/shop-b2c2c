package com.shop.b2b2c.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class PosterUtils {

    private static volatile String CHINESE_FONT_FAMILY = null;

    /**
     * 智能初始化并注册跨平台高品质中文字体 (解决 Linux 无中文字体导致方块口口口乱码问题)
     */
    public static synchronized String resolveChineseFontFamily() {
        if (CHINESE_FONT_FAMILY != null) {
            return CHINESE_FONT_FAMILY;
        }

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

        // 1. 如果是 Linux/Docker 环境，探测常见中文字体文件并主动注册进 JVM
        String[] fontPaths = new String[] {
                "/usr/share/fonts/truetype/windows/msyh.ttc",
                "/usr/share/fonts/truetype/windows/simhei.ttf",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc"
        };
        for (String fp : fontPaths) {
            java.io.File file = new java.io.File(fp);
            if (file.exists() && file.isFile()) {
                try {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, file);
                    ge.registerFont(font);
                    log.info("PosterUtils 成功向 JVM 注册中文字体文件: {}", fp);
                } catch (Exception e) {
                    log.debug("注册字体文件失败: {} - {}", fp, e.getMessage());
                }
            }
        }

        // 2. 获取当前系统已注册的所有字体家族名称
        String[] availableFamilies = ge.getAvailableFontFamilyNames();
        java.util.Set<String> set = new java.util.HashSet<>(java.util.Arrays.asList(availableFamilies));

        // 3. 优先级候选列表：跨平台优质无衬线中文字体
        String[] preferred = new String[] {
                "Microsoft YaHei",          // Windows 默认微软雅黑
                "微软雅黑",
                "SimHei",                   // 黑体
                "黑体",
                "WenQuanYi Micro Hei",      // Linux 文泉驿微米黑
                "文泉驿微米黑",
                "WenQuanYi Zen Hei",        // Linux 文泉驿正黑
                "文泉驿正黑",
                "Noto Sans CJK SC",         // Google Noto 思源黑体简体
                "Source Han Sans CN",       // 思源黑体
                "PingFang SC",              // macOS 苹方
                "苹方-简",
                "STHeiti",                  // 华文黑体
                "Songti SC",                // 宋体
                "SansSerif"                 // 兜底逻辑字体
        };

        for (String fontName : preferred) {
            if (set.contains(fontName)) {
                CHINESE_FONT_FAMILY = fontName;
                log.info("PosterUtils 海报生成引擎已锁定高质量中文字体: [{}]", fontName);
                return fontName;
            }
        }

        CHINESE_FONT_FAMILY = "SansSerif";
        log.warn("PosterUtils 未能匹配到专用中文字体，降级为系统逻辑字体 [SansSerif]");
        return CHINESE_FONT_FAMILY;
    }

    public static Font getFont(int style, int size) {
        return new Font(resolveChineseFontFamily(), style, size);
    }

    /**
     * 过滤 4 字节 Emoji 字符，防止在 Java AWT 文本字体渲染时产生方块方格口口
     */
    public static String sanitizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\ud800\\udc00-\\udbff\\udfff\\ud800-\\udfff]", "").trim();
    }

    /**
     * 生成通用二维码的 Base64 字符串
     */
    public static String generateQrCodeBase64(String content, int width, int height) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix bitMatrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    image.setRGB(x, y, bitMatrix.get(x, y) ? 0xFF1E293B : 0xFFFFFFFF);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.error("生成二维码失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 生成 9:16 微信防封精美代付买单海报 (Base64 Data URL)
     * 分辨率: 750 x 1334
     */
    public static String generatePeerPayPoster(
            String payUrl,
            String storeName,
            String productTitle,
            BigDecimal price,
            String peerMessage,
            String goodsTypeName) {
        try {
            int width = 750;
            int height = 1334;

            BufferedImage poster = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = poster.createGraphics();

            // 开启高质量抗锯齿与文字平滑
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // 1. 背景渐变 (高阶赤红/暖橙色调，吸睛且具备电商质感)
            GradientPaint bgGradient = new GradientPaint(
                    0, 0, new Color(225, 29, 72), // 玫红 Rose 600
                    0, height, new Color(249, 115, 22) // 暖橙 Orange 500
            );
            g2.setPaint(bgGradient);
            g2.fillRect(0, 0, width, height);

            // 2. 装饰光斑圆
            g2.setColor(new Color(255, 255, 255, 30));
            g2.fillOval(-100, -100, 350, 350);
            g2.fillOval(500, 1000, 400, 400);

            // 3. 顶部 Header 标头
            g2.setColor(Color.WHITE);
            g2.setFont(getFont(Font.BOLD, 36));
            g2.drawString("【帮我买单】来自好友的购物求助", 60, 90);

            g2.setFont(getFont(Font.PLAIN, 22));
            g2.setColor(new Color(255, 255, 255, 210));
            g2.drawString("16688 优选供应链 · 担保交易 · 极速代付", 60, 130);

            // 4. 白色主卡片容器 (圆角卡片)
            int cardX = 50;
            int cardY = 170;
            int cardW = 650;
            int cardH = 1080;
            g2.setColor(new Color(0, 0, 0, 40));
            g2.fill(new RoundRectangle2D.Float(cardX + 4, cardY + 8, cardW, cardH, 36, 36)); // 投影

            g2.setColor(Color.WHITE);
            g2.fill(new RoundRectangle2D.Float(cardX, cardY, cardW, cardH, 36, 36));

            // 5. 店铺信息横幅
            g2.setColor(new Color(241, 245, 249)); // Slate 100
            g2.fill(new RoundRectangle2D.Float(cardX + 30, cardY + 35, cardW - 60, 60, 20, 20));
            g2.setColor(new Color(71, 85, 105)); // Slate 600
            g2.setFont(getFont(Font.BOLD, 22));
            String safeStoreName = sanitizeText(storeName);
            String storeDisplay = "【优选好店】 " + (!safeStoreName.isEmpty() ? safeStoreName : "官方授权优选店");
            g2.drawString(storeDisplay, cardX + 50, cardY + 73);

            // 6. 买家求助留言气泡
            int bubbleY = cardY + 120;
            g2.setColor(new Color(254, 243, 199)); // Amber 100
            g2.fill(new RoundRectangle2D.Float(cardX + 30, bubbleY, cardW - 60, 100, 24, 24));
            g2.setColor(new Color(180, 83, 9)); // Amber 700
            g2.setFont(getFont(Font.BOLD, 22));
            g2.drawString("好友代付留言：", cardX + 55, bubbleY + 40);
            g2.setFont(getFont(Font.PLAIN, 22));
            String msg = sanitizeText(peerMessage);
            if (msg.isEmpty()) {
                msg = "好心人帮我买个单呗，救急谢谢老铁！";
            }
            if (msg.length() > 24) msg = msg.substring(0, 24) + "...";
            g2.drawString("“" + msg + "”", cardX + 55, bubbleY + 75);

            // 7. 商品展示卡片
            int prodBoxY = cardY + 245;
            g2.setColor(new Color(248, 250, 252));
            g2.fill(new RoundRectangle2D.Float(cardX + 30, prodBoxY, cardW - 60, 260, 24, 24));

            // 商品类型徽章
            g2.setColor(new Color(225, 29, 72));
            g2.fill(new RoundRectangle2D.Float(cardX + 55, prodBoxY + 30, 140, 36, 12, 12));
            g2.setColor(Color.WHITE);
            g2.setFont(getFont(Font.BOLD, 18));
            String safeGoodsTypeName = sanitizeText(goodsTypeName);
            g2.drawString(!safeGoodsTypeName.isEmpty() ? safeGoodsTypeName : "精选好物", cardX + 75, prodBoxY + 55);

            // 商品标题
            g2.setColor(new Color(15, 23, 42)); // Slate 900
            g2.setFont(getFont(Font.BOLD, 28));
            String title = sanitizeText(productTitle);
            if (title.isEmpty()) title = "精选商品";
            if (title.length() > 18) {
                g2.drawString(title.substring(0, 18), cardX + 55, prodBoxY + 115);
                String sub = title.length() > 36 ? title.substring(18, 35) + "..." : title.substring(18);
                g2.drawString(sub, cardX + 55, prodBoxY + 155);
            } else {
                g2.drawString(title, cardX + 55, prodBoxY + 125);
            }

            // 代付应付金额 (醒目大字体)
            g2.setColor(new Color(225, 29, 72));
            g2.setFont(getFont(Font.BOLD, 24));
            g2.drawString("代付总额: ¥", cardX + 55, prodBoxY + 225);
            g2.setFont(getFont(Font.BOLD, 48));
            g2.drawString(price != null ? price.setScale(2).toString() : "0.00", cardX + 185, prodBoxY + 225);

            // 8. 微信支付二维码区域
            int qrSize = 300;
            int qrX = cardX + (cardW - qrSize) / 2;
            int qrY = cardY + 540;

            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix bitMatrix = new MultiFormatWriter().encode(payUrl, BarcodeFormat.QR_CODE, qrSize, qrSize, hints);
            BufferedImage qrImg = new BufferedImage(qrSize, qrSize, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < qrSize; x++) {
                for (int y = 0; y < qrSize; y++) {
                    qrImg.setRGB(x, y, bitMatrix.get(x, y) ? 0xFF0F172A : 0xFFFFFFFF);
                }
            }
            g2.drawImage(qrImg, qrX, qrY, null);

            // 9. 底部说明文字
            g2.setColor(new Color(15, 23, 42));
            g2.setFont(getFont(Font.BOLD, 26));
            String callToAction = "长按识别上方二维码 · 极速替TA买单";
            int ctaWidth = g2.getFontMetrics().stringWidth(callToAction);
            g2.drawString(callToAction, cardX + (cardW - ctaWidth) / 2, qrY + qrSize + 48);

            g2.setFont(getFont(Font.PLAIN, 20));
            g2.setColor(new Color(100, 116, 139));
            String tip = "支持微信 / 支付宝支付 · 代付人免注册";
            int tipWidth = g2.getFontMetrics().stringWidth(tip);
            g2.drawString(tip, cardX + (cardW - tipWidth) / 2, qrY + qrSize + 85);

            // 10. 底部防伪防封印标
            g2.setColor(new Color(255, 255, 255, 180));
            g2.setFont(getFont(Font.PLAIN, 18));
            String footer = "安全提示：代付商品将直接发货给买家，请核对商品后完成付款";
            int footW = g2.getFontMetrics().stringWidth(footer);
            g2.drawString(footer, (width - footW) / 2, height - 35);

            g2.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(poster, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.error("生成代付海报图片失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
