package com.woowacamp.storage.domain.folder.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.woowacamp.storage.domain.folder.dto.FolderContentsDto;
import com.woowacamp.storage.domain.folder.dto.FolderCreateResponseDto;
import com.woowacamp.storage.domain.folder.dto.GetFolderContentsRequestParams;
import com.woowacamp.storage.domain.folder.dto.request.CreateFolderReqDto;
import com.woowacamp.storage.domain.folder.dto.request.FolderMoveDto;
import com.woowacamp.storage.domain.folder.service.FolderService;
import com.woowacamp.storage.global.annotation.CheckDto;
import com.woowacamp.storage.global.annotation.CheckField;
import com.woowacamp.storage.global.annotation.RequestType;
import com.woowacamp.storage.global.aop.type.FieldType;
import com.woowacamp.storage.global.aop.type.FileType;
import com.woowacamp.storage.global.constant.PermissionType;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/folders")
public class FolderController {

	private final FolderService folderService;

	@RequestType(permission = PermissionType.WRITE, fileType = FileType.FOLDER)
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping
	public FolderCreateResponseDto createFolder(@CheckDto @Valid @RequestBody CreateFolderReqDto req,
		HttpServletResponse response) {
		Long folder = folderService.createFolder(req);
		return new FolderCreateResponseDto(folder);
		// response.setHeader("Location", UrlUtil.getAbsoluteUrl("/api/v1/folders/" + folder));
	}

	@RequestType(permission = PermissionType.READ, fileType = FileType.FOLDER)
	@GetMapping("/{folderId}")
	public FolderContentsDto getFolderContents(@CheckField(value = FieldType.FOLDER_ID) @PathVariable Long folderId,
		@CheckDto @Valid @ModelAttribute GetFolderContentsRequestParams request) {

		folderService.checkFolderOwnedBy(folderId, request.userId());

		return folderService.getFolderContents(folderId, request.cursorId(), request.cursorType(), request.limit(),
			request.sortBy(), request.sortDirection(), request.localDateTime(), request.size(),
			Objects.equals(request.userId(), request.creatorId()));
	}

	@RequestType(permission = PermissionType.WRITE, fileType = FileType.FOLDER)
	@PatchMapping("/{folderId}")
	public void moveFolder(@PathVariable("folderId") @CheckField(value = FieldType.FOLDER_ID) Long sourceFolderId,
		@CheckDto @RequestBody FolderMoveDto dto) {
		folderService.moveFolder(sourceFolderId, dto);
	}

	@RequestType(permission = PermissionType.WRITE, fileType = FileType.FOLDER)
	@DeleteMapping("/{folderId}")
	@ResponseStatus(HttpStatus.OK)
	public void delete(@CheckField(FieldType.FOLDER_ID) @PathVariable Long folderId,
		@CheckField(FieldType.USER_ID) @RequestParam Long userId) {
		folderService.deleteFolder(folderId, userId);
	}

}
