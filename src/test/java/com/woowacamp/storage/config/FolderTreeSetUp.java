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
	private FolderMetadata longestFolder;

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
				.parentFolderId(null)
				.permissionType(
					PermissionType.WRITE)
				.idFullPath("/")
				.nameFullPath("/")
				.namePathLength(1)
				.build());

		subFolders = new ArrayList<>();
		files = new ArrayList<>();

		for (int i = 0; i < 7; i++) {
			String folderName = "Sub Folder " + (i + 1);
			String nameFullPath = rootFolder.getNameFullPath() + folderName + "/";
			FolderMetadata folderMetadata = folderMetadataRepository.save(FolderMetadata.builder()
				.rootId(rootFolder.getId())
				.creatorId(userId)
				.createdAt(now.minusDays(i))
				.updatedAt(now)
				.parentFolderId(rootFolder.getId())
				.uploadFolderName(folderName)
				.sharingExpiredAt(now)
				.size(1000)
				.ownerId(userId)
				.permissionType(PermissionType.WRITE)
				.nameFullPath(nameFullPath)
				.namePathLength(nameFullPath.length())
				.idFullPath(rootFolder.getIdFullPath())
				.build());
			folderMetadata.updateIdFullPath(rootFolder.getIdFullPath());
			folderMetadataRepository.save(folderMetadata);
			subFolders.add(folderMetadata);
		}
		String subFolderNmae = "Sub Folder's Sub Folder";
		String subFolderFullPath = subFolders.get(0).getNameFullPath() + subFolderNmae + "/";
		subSubFolder = folderMetadataRepository.save(FolderMetadata.builder()
			.rootId(rootFolder.getId())
			.creatorId(userId)
			.createdAt(now.minusDays(1))
			.updatedAt(now)
			.parentFolderId(subFolders.get(0).getId())
			.uploadFolderName(subFolderNmae)
			.sharingExpiredAt(now)
			.size(1000)
			.ownerId(userId)
			.permissionType(PermissionType.WRITE)
			.nameFullPath(subFolderFullPath)
			.namePathLength(subFolderFullPath.length())
			.idFullPath(subFolders.get(0).getIdFullPath())
			.build());
		subSubFolder.updateIdFullPath(subFolders.get(0).getIdFullPath());
		folderMetadataRepository.save(subSubFolder);

		for (int i = 0; i < 7; i++) {
			for (int j = 0; j < 2; j++) {
				String fileName = "File " + i + j;
				String nameFullPath = subFolders.get(i).getNameFullPath() + fileName+"/";

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
					.uploadFileName(fileName)
					.sharingExpiredAt(now)
					.permissionType(PermissionType.WRITE)
					.nameFullPath(nameFullPath)
					.namePathLength(nameFullPath.length())
					.idFullPath(subFolders.get(i).getIdFullPath())
					.build());
				fileMetadata.updateIdFullPath(subFolders.get(i).getIdFullPath());

				fileMetadataJpaRepository.save(fileMetadata);
				files.add(fileMetadata);
			}
		}
		for (int i = 0; i < 2; i++) {
			String fileName = "File Sub " + i;
			String nameFullPath = subSubFolder.getNameFullPath() + fileName+"/";
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
				.uploadFileName(fileName)
				.sharingExpiredAt(now)
				.permissionType(PermissionType.WRITE)
				.nameFullPath(nameFullPath)
				.namePathLength(nameFullPath.length())
				.idFullPath(subSubFolder.getIdFullPath())
				.build());
			fileMetadata.updateIdFullPath(subSubFolder.getIdFullPath());
			fileMetadataJpaRepository.save(fileMetadata);
		}

		FolderMetadata sub1 = subFolders.get(0);
		sub1.addSize(1000);
		folderMetadataRepository.save(sub1);

		FolderMetadata parent = subFolders.get(0);
		for (int i = 0; i < 3; i++) {
			String folderName = "";
			int range = Math.min(100, 250 - parent.getNamePathLength());
			for (int j = 1; j < range; j++) {
				folderName += "a";
			}

			String nameFullPath = parent.getNameFullPath() + folderName + "/";
			FolderMetadata folderMetadata = folderMetadataRepository.save(FolderMetadata.builder()
				.rootId(rootFolder.getId())
				.creatorId(userId)
				.createdAt(now.minusDays(1))
				.updatedAt(now)
				.parentFolderId(parent.getId())
				.uploadFolderName(folderName)
				.sharingExpiredAt(now)
				.size(1000)
				.ownerId(userId)
				.permissionType(PermissionType.WRITE)
				.nameFullPath(nameFullPath)
				.namePathLength(nameFullPath.length())
				.idFullPath(parent.getIdFullPath())
				.build());
			folderMetadata.updateIdFullPath(parent.getIdFullPath());
			folderMetadataRepository.save(folderMetadata);

			parent = folderMetadata;
			longestFolder = folderMetadata;
		}

	}

}
