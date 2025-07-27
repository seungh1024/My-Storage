package com.woowacamp.storage.domain.folder.event;

import com.woowacamp.storage.domain.folder.entity.FolderMetadata;

import lombok.Getter;

@Getter
public class FolderMoveEvent {
	private final FolderMetadata sourceFolder;
	private final FolderMetadata targetFolder;

	public FolderMoveEvent(FolderMetadata sourceFolder, FolderMetadata targetFolder) {
		this.sourceFolder = sourceFolder;
		this.targetFolder = targetFolder;
	}
}
