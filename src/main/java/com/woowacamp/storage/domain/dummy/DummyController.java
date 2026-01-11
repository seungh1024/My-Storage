package com.woowacamp.storage.domain.dummy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/dummy")
public class DummyController {
	private final DummyService dummyService;

	@ResponseStatus(HttpStatus.ACCEPTED)
	@PostMapping("/user")
	public void createUserDummy(@RequestBody DummyRequestUserDto userDto) {
		dummyService.createUserDummy(userDto);
	}

	@ResponseStatus(HttpStatus.ACCEPTED)
	@PostMapping("/folder")
	public void createFolderDummy(@RequestBody DummyRequestFolderDto folderDto) {
		dummyService.createFolderDummy(folderDto.startId());
	}

}
