package com.example.shopsecure.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.shopsecure.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

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

            return (String) uploadResult.get("secure_url");

        } catch (IOException e) {
            throw new BusinessException("Image upload failed: " + e.getMessage()); // ← changed
        }
    }

    public void delete(String imageUrl) {
        try {

            String publicId = imageUrl
                    .replaceAll("https://res.cloudinary.com/[^/]+/image/upload/v\\d+/", "")
                    .replaceAll("\\.[^.]+$", "");

            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());

        } catch (IOException e) {
            throw new BusinessException("Image deletion failed: " + e.getMessage()); // ← changed
        }
    }
}