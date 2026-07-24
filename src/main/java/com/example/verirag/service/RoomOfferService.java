package com.example.verirag.service;

import com.example.verirag.common.PageResult;
import com.example.verirag.dto.RoomOfferImportResult;
import com.example.verirag.dto.RoomOfferSaveRequest;
import com.example.verirag.dto.RoomOfferStats;
import com.example.verirag.dto.RoomOfferView;
import com.example.verirag.entity.OfferImportBatch;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface RoomOfferService {
    PageResult<RoomOfferView> page(String keyword, Long residenceId, String status,
                                   int page, int size);

    RoomOfferView get(Long id);

    RoomOfferStats stats();

    void save(RoomOfferSaveRequest request);

    void delete(Long id);

    RoomOfferImportResult importWorkbook(MultipartFile file, Long uploadUserId);

    List<OfferImportBatch> recentImports(int limit);
}
