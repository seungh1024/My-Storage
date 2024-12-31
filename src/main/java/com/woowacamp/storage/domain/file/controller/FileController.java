package com.woowacamp.storage.domain.file.controller;

import java.net.URL;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.woowacamp.storage.domain.file.dto.FileMoveDto;
import com.woowacamp.storage.domain.file.dto.request.FileUploadCompleteRequestDto;
import com.woowacamp.storage.domain.file.dto.request.FileUploadRequestDto;
import com.woowacamp.storage.domain.file.dto.response.FileUploadResponseDto;
import com.woowacamp.storage.domain.file.service.FileService;
import com.woowacamp.storage.domain.file.service.S3FileService;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.global.annotation.CheckDto;
import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.annotation.RequestType;
import com.woowacamp.storage.global.aop.type.FieldType;
import com.woowacamp.storage.global.aop.type.FileType;
import com.woowacamp.storage.global.constant.PermissionType;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
public class FileController {

	private final FileService fileService;
	private final S3FileService s3FileService;

	@RequestType(permission = PermissionType.WRITE, fileType = FileType.FILE)
	@PatchMapping("/{fileId}")
	public void moveFile(@CheckField(FieldType.FILE_ID) @PathVariable Long fileId,
		@CheckDto @RequestBody FileMoveDto dto) {
		fileService.getFileMetadataBy(fileId, dto.userId());
		fileService.moveFile(fileId, dto);
	}

	@RequestType(permission = PermissionType.WRITE, fileType = FileType.FILE)
	@DeleteMapping("/{fileId}")
	@ResponseStatus(HttpStatus.OK)
	public void delete(@CheckField(FieldType.FILE_ID) @PathVariable Long fileId,
		@CheckField(FieldType.USER_ID) @RequestParam Long userId) {
		fileService.deleteFile(fileId, userId);
	}

	@RequestType(permission = PermissionType.WRITE, fileType = FileType.FOLDER)
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public FileUploadResponseDto create(@CheckDto @RequestBody FileUploadRequestDto fileUploadRequestDto) {
		return s3FileService.createInitialMetadata(fileUploadRequestDto);
	}

	/**
	 * 업로드 중간에 공유 기간이 만료될 수 있으니 AOP로 확인하지 않음.
	 * userId를 별도로 받아서 자신이 업로드한 파일인지만 확인 후 업로드 완료 진행.
	 */
	@PatchMapping("/complete/{fileId}")
	@ResponseStatus(HttpStatus.OK)
	public void createComplete(@PathVariable long fileId, @RequestBody FileUploadCompleteRequestDto requestDto) {
		s3FileService.createComplete(fileId, requestDto.userId(), requestDto.objectKey());
	}

	@RequestType(permission = PermissionType.READ, fileType = FileType.FILE)
	@GetMapping("/{fileId}")
	@ResponseStatus(HttpStatus.OK)
	public URL getFileUrl(@CheckField(FieldType.FILE_ID) @PathVariable long fileId,
		@CheckField(FieldType.USER_ID) @RequestParam long userId) {
		return s3FileService.getFileUrl(fileId);
	}
}
