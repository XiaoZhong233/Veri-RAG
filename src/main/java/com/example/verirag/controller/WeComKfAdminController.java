package com.example.verirag.controller;

import com.example.verirag.common.R;
import com.example.verirag.integration.wecom.WeComKfApiClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 微信客服账号和接待人员管理，仅管理员可操作。 */
@RestController
@RequestMapping("/api/wecom/kf/admin")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(prefix = "wecom.kf", name = "enabled", havingValue = "true")
public class WeComKfAdminController {

    private final WeComKfApiClient apiClient;

    public WeComKfAdminController(WeComKfApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @GetMapping("/accounts")
    public R<List<WeComKfApiClient.KfAccount>> accounts() {
        return R.ok(apiClient.listAccounts());
    }

    @GetMapping("/members")
    public R<List<WeComKfApiClient.KfMemberId>> members() {
        return R.ok(apiClient.listVisibleMemberIds());
    }

    @GetMapping("/accounts/{openKfId}/servicers")
    public R<List<WeComKfApiClient.KfServicer>> servicers(
            @PathVariable String openKfId) {
        return R.ok(apiClient.listServicers(openKfId));
    }

    @PostMapping("/accounts/{openKfId}/servicers")
    public R<List<WeComKfApiClient.KfServicerResult>> addServicers(
            @PathVariable String openKfId,
            @Valid @RequestBody ServicerRequest request) {
        return R.ok(apiClient.addServicers(openKfId, request.userIds()));
    }

    @DeleteMapping("/accounts/{openKfId}/servicers")
    public R<List<WeComKfApiClient.KfServicerResult>> removeServicers(
            @PathVariable String openKfId,
            @Valid @RequestBody ServicerRequest request) {
        return R.ok(apiClient.removeServicers(openKfId, request.userIds()));
    }

    public record ServicerRequest(
            @NotEmpty(message = "至少填写一个接待人员 userid")
            @Size(max = 100, message = "每次最多添加或移除100名接待人员")
            List<@NotBlank(message = "接待人员 userid 不能为空") String> userIds) {
    }
}
