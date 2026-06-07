package com.example.shopsecure.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    private static final int MAX_WIDTH = 1024;
    private static final int MAX_HEIGHT = 1024;
    private static final float COMPRESSION_QUALITY = 0.7f;

    public String upload(MultipartFile file) {
        try {
            byte[] compressedImage = compressImage(file);

            Map result = cloudinary.uploader().upload(
                    compressedImage,
                    ObjectUtils.asMap("folder", "shopsecure/products")
            );

            return (String) result.get("secure_url");

        } catch (IOException e) {
            throw new RuntimeException("Image upload failed: " + e.getMessage());
        }
    }

    private byte[] compressImage(MultipartFile file) throws IOException {

        BufferedImage original;

        try {
            original = ImageIO.read(file.getInputStream());

            if (original == null) {
                throw new RuntimeException("Unsupported image format or corrupted file");
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to read image: " + e.getMessage());
        }

        // 🔥 FIX: Convert to RGB to avoid CMYK / color space errors
        BufferedImage rgbImage = convertToRGB(original);

        // Resize image
        BufferedImage resized = resizeImage(rgbImage);

        // Compress to JPG
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream);

        writer.setOutput(imageOutputStream);

        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(COMPRESSION_QUALITY);

        writer.write(null, new IIOImage(resized, null, null), params);

        writer.dispose();
        imageOutputStream.close();

        return outputStream.toByteArray();
    }

    private BufferedImage convertToRGB(BufferedImage image) {

        BufferedImage rgbImage = new BufferedImage(
                image.getWidth(),
                image.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g = rgbImage.createGraphics();

        // white background for transparency-safe conversion
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        g.drawImage(image, 0, 0, null);
        g.dispose();

        return rgbImage;
    }

    private BufferedImage resizeImage(BufferedImage original) {

        int width = original.getWidth();
        int height = original.getHeight();

        // no resize needed
        if (width <= MAX_WIDTH && height <= MAX_HEIGHT) {
            return original;
        }

        double aspectRatio = (double) width / height;

        if (width > height) {
            width = MAX_WIDTH;
            height = (int) (MAX_WIDTH / aspectRatio);
        } else {
            height = MAX_HEIGHT;
            width = (int) (MAX_HEIGHT * aspectRatio);
        }

        BufferedImage resized = new BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g.drawImage(original, 0, 0, width, height, null);
        g.dispose();

        return resized;
    }

    public void delete(String imageUrl) {
        try {
            String publicId = imageUrl
                    .replaceAll("https://res.cloudinary.com/[^/]+/image/upload/v\\d+/", "")
                    .replaceAll("\\.[^.]+$", "");

            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());

        } catch (IOException e) {
            throw new RuntimeException("Image deletion failed: " + e.getMessage());
        }
    }
}