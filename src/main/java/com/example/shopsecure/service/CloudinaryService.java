package com.example.shopsecure.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.shopsecure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public String upload(MultipartFile file) {
        try {

            Map uploadResult = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", "shopsecure/products",
                            "fetch_format", "auto",
                            "quality", "auto",
                            "resource_type", "image"
                    )
            );

            log.info("Image uploaded: size={} bytes", file.getSize());
            return (String) uploadResult.get("secure_url");

        } catch (IOException e) {
            log.error("Image upload failed: size={} bytes", file.getSize(), e);
            throw new BusinessException("Image upload failed: " + e.getMessage()); // ← changed
        }
    }

    public void delete(String imageUrl) {
        String publicId = imageUrl
                .replaceAll("https://res.cloudinary.com/[^/]+/image/upload/v\\d+/", "")
                .replaceAll("\\.[^.]+$", "");
        try {

            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            log.info("Image deleted: publicId={}", publicId);

        } catch (IOException e) {
            log.error("Image deletion failed: publicId={}", publicId, e);
            throw new BusinessException("Image deletion failed: " + e.getMessage()); // ← changed
        }
    }
}