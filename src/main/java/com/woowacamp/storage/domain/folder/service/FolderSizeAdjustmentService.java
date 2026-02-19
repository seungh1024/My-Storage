package com.woowacamp.storage.domain.folder.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.woowacamp.storage.domain.folder.repository.FolderMetadataRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FolderSizeAdjustmentService {

	private final FolderMetadataRepository folderMetadataRepository;

	public Map<Long, Long> mergeDeltaByFolderIds(List<Long> sourceAncestorIds, List<Long> targetAncestorIds, long delta) {
		Map<Long, Long> deltaByFolderId = new LinkedHashMap<>();

		for (Long folderId : sourceAncestorIds) {
			deltaByFolderId.merge(folderId, -delta, Long::sum);
		}
		for (Long folderId : targetAncestorIds) {
			deltaByFolderId.merge(folderId, delta, Long::sum);
		}
		return deltaByFolderId;
	}

	@Transactional
	public void applySizeDeltas(Map<Long, Long> deltaByFolderId) {
		if (deltaByFolderId == null || deltaByFolderId.isEmpty()) {
			return;
		}

		Map<Long, Long> filteredDeltaByFolderId = deltaByFolderId.entrySet().stream()
			.filter(entry -> entry.getValue() != 0L)
			.collect(Collectors.toMap(
				Map.Entry::getKey,
				Map.Entry::getValue,
				(first, second) -> second,
				LinkedHashMap::new
			));

		if (filteredDeltaByFolderId.isEmpty()) {
			return;
		}
		folderMetadataRepository.batchUpdateSizeDeltas(filteredDeltaByFolderId);
	}
}

