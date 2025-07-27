package com.woowacamp.storage.domain.message.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class MessageInfoId implements Serializable {
	private UUID uuid;

	@Column(name = "folder_metadata_id")
	private Long folderMetadataId;

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MessageInfoId)) return false;
		MessageInfoId that = (MessageInfoId) o;
		return Objects.equals(uuid, that.uuid) &&
			Objects.equals(folderMetadataId, that.folderMetadataId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(uuid, folderMetadataId);
	}

}
