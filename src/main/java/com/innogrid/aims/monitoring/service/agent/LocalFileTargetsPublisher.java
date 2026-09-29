package com.innogrid.aims.monitoring.service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.innogrid.aims.monitoring.entity.AgentNode;
import com.innogrid.aims.monitoring.entity.AgentTransport;
import org.springframework.stereotype.Component;

/**
 * 백엔드와 Alloy 가 같은 서버일 때 — 백엔드가 {@code targets_path} 파일을 직접 쓴다.
 * 같은 디렉터리의 임시 파일에 쓴 뒤 원자적으로 바꿔서, Alloy 의 {@code discovery.file} 이 반쯤 쓰인 파일을 읽는 일이 없게 한다.
 */
@Component
public class LocalFileTargetsPublisher implements AgentTargetsPublisher {

    @Override
    public AgentTransport transport() {
        return AgentTransport.LOCAL;
    }

    @Override
    public String currentHash(AgentNode node) throws IOException {
        Path path = pathOf(node);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return CollectTargetsBuilder.sha256(Files.readString(path, StandardCharsets.UTF_8));
    }

    @Override
    public void publish(AgentNode node, String json) throws IOException {
        Path path = pathOf(node);
        Path directory = path.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path pathOf(AgentNode node) {
        if (node.getTargetsPath() == null || node.getTargetsPath().isBlank()) {
            throw new IllegalStateException("targets_path 가 비어 있습니다: asset " + node.getAssetId());
        }
        return Path.of(node.getTargetsPath());
    }
}
