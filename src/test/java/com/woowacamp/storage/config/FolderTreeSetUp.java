package com.woowacamp.storage.config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.woowacamp.storage.domain.file.entity.FileMetadata;
import com.woowacamp.storage.domain.file.repository.FileMetadataJpaRepository;
import com.woowacamp.storage.domain.folder.entity.FolderMetadata;
import com.woowacamp.storage.domain.folder.repository.FolderMetadataJpaRepository;
import com.woowacamp.storage.global.constant.PermissionType;
import com.woowacamp.storage.global.constant.UploadStatus;

import jakarta.annotation.PostConstruct;
import lombok.Getter;

@Getter
@Component
public class FolderTreeSetUp {
	@Autowired
	private FolderMetadataJpaRepository folderMetadataRepository;

	@Autowired
	private FileMetadataJpaRepository fileMetadataJpaRepository;

	private FolderMetadata rootFolder;
	private List<FolderMetadata> subFolders;
	private List<FileMetadata> files;
	private LocalDateTime now;
	private FolderMetadata subSubFolder;
	private long userId = 1L;

	// @PostConstruct
	public void folderTreeSetUp() {
		now = LocalDateTime.now();
		fileMetadataJpaRepository.deleteAll();
		folderMetadataRepository.deleteAll();

		rootFolder = folderMetadataRepository.save(
			FolderMetadata.builder()
				.createdAt(now)
				.updatedAt(now)
				.uploadFolderName("Parent Folder")
				.sharingExpiredAt(now)
				.ownerId(userId)
				.size(8000)
				.permissionType(
					PermissionType.WRITE)
				.build());

		subFolders = new ArrayList<>();
		files = new ArrayList<>();

		for (int i = 0; i < 7; i++) {
			FolderMetadata folderMetadata = folderMetadataRepository.save(FolderMetadata.builder()
				.rootId(rootFolder.getId())
				.creatorId(userId)
				.createdAt(now.minusDays(i))
				.updatedAt(now)
				.parentFolderId(rootFolder.getId())
				.uploadFolderName("Sub Folder " + (i + 1))
				.sharingExpiredAt(now)
				.size(1000)
				.ownerId(userId)
				.permissionType(PermissionType.WRITE)
				.build());
			folderMetadataRepository.save(folderMetadata);
			subFolders.add(folderMetadata);
		}
		subSubFolder = folderMetadataRepository.save(FolderMetadata.builder()
			.rootId(rootFolder.getId())
			.creatorId(userId)
			.createdAt(now.minusDays(1))
			.updatedAt(now)
			.parentFolderId(subFolders.get(0).getId())
			.uploadFolderName("Sub Folder's Sub Folder")
			.sharingExpiredAt(now)
			.size(1000)
			.ownerId(userId)
			.permissionType(PermissionType.WRITE)
			.build());

		folderMetadataRepository.save(subSubFolder);

		for (int i = 0; i < 7; i++) {
			for (int j = 0; j < 2; j++) {
				FileMetadata fileMetadata = fileMetadataJpaRepository.save(FileMetadata.builder()
					.rootId(rootFolder.getId())
					.uuidFileName("uuidFileName" + i + j)
					.creatorId(userId)
					.fileType("file")
					.ownerId(userId)
					.createdAt(now.minusHours(i))
					.updatedAt(now)
					.fileSize(500L)
					.parentFolderId(subFolders.get(i).getId())
					.uploadStatus(UploadStatus.SUCCESS)
					.uploadFileName("File " + i + j)
					.sharingExpiredAt(now)
					.permissionType(PermissionType.WRITE)
					.build());
				fileMetadataJpaRepository.save(fileMetadata);
				files.add(fileMetadata);
			}
		}
		for (int i = 0; i < 2; i++) {
			FileMetadata fileMetadata = fileMetadataJpaRepository.save(FileMetadata.builder()
				.rootId(rootFolder.getId())
				.uuidFileName("uuidFileName Sub " + i)
				.creatorId(userId)
				.fileType("file")
				.ownerId(userId)
				.createdAt(now.minusHours(i))
				.updatedAt(now)
				.fileSize(500L)
				.parentFolderId(subSubFolder.getId())
				.uploadStatus(UploadStatus.SUCCESS)
				.uploadFileName("File Sub " + i)
				.sharingExpiredAt(now)
				.permissionType(PermissionType.WRITE)
				.build());
			fileMetadataJpaRepository.save(fileMetadata);
		}

		FolderMetadata sub1 = subFolders.get(0);
		sub1.addSize(1000);
		folderMetadataRepository.save(sub1);
	}

}
