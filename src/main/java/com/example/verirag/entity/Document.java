package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long categoryId;
    private String title;
    private String fileName;
    /** 相对 uploads 根的路径 */
    private String filePath;
    private String fileType;
    private Long fileSize;
    /** PROCESSING / SUCCESS / FAIL */
    private String status;
    private Integer vectorCount;
    private Long uploadUserId;
    private LocalDateTime createTime;
}
