package com.example.verirag.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_residence_detail")
public class ResidenceDetail {
    @TableId
    private Long residenceId;
    private String officialId;
    private String postcode;
    private String transportLines;
    private String officialUrl;
    private String pageTags;
    private String facilities;
    private String detailMarkdown;
    private String sourceFileName;
    private LocalDateTime detailUpdatedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
