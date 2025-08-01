package com.talentcloud.profile.controller;

import com.talentcloud.profile.dto.UpdateCertificationDto;
import com.talentcloud.profile.iservice.IServiceCertification;
import com.talentcloud.profile.model.Certification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/certifications")
public class CertificationController {

    private final IServiceCertification certificationService;

    @Autowired
    public CertificationController(IServiceCertification certificationService) {
        this.certificationService = certificationService;
    }

    @GetMapping("/{certificationId}")
    public ResponseEntity<Certification> getCertificationById(@PathVariable Long certificationId) {
        return certificationService.getCertificationById(certificationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    @GetMapping("/candidate/{candidateId}")
    public ResponseEntity<List<Certification>> getAllCertificationsByCandidateId(@PathVariable Long candidateId) {
        List<Certification> certifications = certificationService.getAllCertificationsByCandidateId(candidateId);
        return ResponseEntity.ok(certifications);
    }


    @PostMapping("/create/{candidateId}")
    public ResponseEntity<Certification> addCertification(@PathVariable Long candidateId, @RequestBody @Valid Certification certification) {
        Certification savedCertification = certificationService.addCertification(certification, candidateId);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedCertification);
    }   


    @PutMapping("/{certificationId}")
    public ResponseEntity<Certification> updateCertification(@PathVariable Long certificationId, @RequestBody @Valid UpdateCertificationDto dto) {
        Certification updatedCertification = certificationService.updateCertification(certificationId, dto);
        return ResponseEntity.ok(updatedCertification);
    }


    @DeleteMapping("/{certificationId}")
    public ResponseEntity<Void> deleteCertification(@PathVariable Long certificationId) {
        certificationService.deleteCertification(certificationId);
        return ResponseEntity.noContent().build();
    }
}