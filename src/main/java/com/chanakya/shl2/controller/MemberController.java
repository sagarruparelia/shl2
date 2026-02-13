package com.chanakya.shl2.controller;

import com.chanakya.shl2.model.dto.request.UpdateMemberPreferencesRequest;
import com.chanakya.shl2.model.dto.response.AccessLogEntry;
import com.chanakya.shl2.model.dto.response.MemberPreferencesResponse;
import com.chanakya.shl2.model.dto.response.MemberShlSummary;
import com.chanakya.shl2.service.AccessLogService;
import com.chanakya.shl2.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/member/{patientId}")
public class MemberController {

    private final MemberService memberService;
    private final AccessLogService accessLogService;

    public MemberController(MemberService memberService, AccessLogService accessLogService) {
        this.memberService = memberService;
        this.accessLogService = accessLogService;
    }

    @GetMapping("/shls")
    public Flux<MemberShlSummary> listShls(@PathVariable String patientId) {
        return memberService.listShlsForMember(patientId);
    }

    @DeleteMapping("/shls/{shlId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deactivateShl(@PathVariable String patientId,
                                     @PathVariable String shlId) {
        return memberService.deactivateShl(patientId, shlId);
    }

    @GetMapping("/access-log")
    public Flux<AccessLogEntry> getAccessLog(@PathVariable String patientId) {
        return accessLogService.getAccessLogForMember(patientId);
    }

    @GetMapping("/shls/{shlId}/access-log")
    public Flux<AccessLogEntry> getShlAccessLog(@PathVariable String patientId,
                                                 @PathVariable String shlId) {
        return accessLogService.getAccessLogForShl(patientId, shlId);
    }

    @GetMapping("/preferences")
    public Mono<MemberPreferencesResponse> getPreferences(@PathVariable String patientId) {
        return memberService.getPreferences(patientId);
    }

    @PutMapping("/preferences")
    public Mono<MemberPreferencesResponse> updatePreferences(@PathVariable String patientId,
                                                              @Valid @RequestBody UpdateMemberPreferencesRequest request) {
        return memberService.updatePreferences(patientId, request);
    }
}
