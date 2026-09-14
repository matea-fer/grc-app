package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * Sadrzaj jedne prilozene datoteke, odvojen od njezinih podataka ({@link Attachment}).
 *
 * Razlog je jedan: popis priloga se crta uz svaki redak tablice zapisa, a bajtovi u istoj
 * tablici znacili bi da svako takvo listanje povuce i sve datoteke. Odvojena tablica to ne
 * cini nemogucim slucajno nego STRUKTURNO - sadrzaj se ne moze dobiti bez drugog upita,
 * pa se ne moze ni zaboraviti izostaviti. Isto bi se moglo postici pazljivo pisanim
 * projekcijama, ali njih mora pogoditi svaki buduci upit; ovo ne mora nitko.
 *
 * Id je isti kao id priloga i dodjeljuje se rucno - sadrzaj bez svog priloga nema smisla,
 * pa nema ni razloga za vlastiti brojac.
 */
@Entity
@Table(name = "attachment_content")
public class AttachmentContent {

    @Id
    @Column(name = "attachment_id")
    private Long attachmentId;

    @Lob
    @Column(nullable = false)
    private byte[] data;

    public AttachmentContent() {
    }

    public AttachmentContent(Long attachmentId, byte[] data) {
        this.attachmentId = attachmentId;
        this.data = data;
    }

    public Long getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(Long attachmentId) {
        this.attachmentId = attachmentId;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}
