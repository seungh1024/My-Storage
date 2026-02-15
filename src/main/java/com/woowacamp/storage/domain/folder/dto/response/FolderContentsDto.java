package com.woowacamp.storage.domain.folder.dto.response;

import java.util.List;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;

public record FolderContentsDto(List<FolderMetadata> folderMetadataList, List<FileMetadata> fileMetadataList) {
}

