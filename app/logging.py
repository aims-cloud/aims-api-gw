"""
Structured logging configuration using structlog.

Provides consistent, structured logging across the application with
JSON output for production environments and human-readable output for development.
"""
import logging
import logging.handlers
import sys
from pathlib import Path
from typing import Any

import structlog
from structlog.types import EventDict, Processor


def add_app_context(logger: Any, method_name: str, event_dict: EventDict) -> EventDict:
    """Add application context to all log entries."""
    event_dict["app"] = "aims-api-gw"
    return event_dict


def configure_logging(
    log_level: str = "INFO",
    json_logs: bool = False,
    log_to_file: bool = False,
    log_file_path: str = "logs/aims-api-gw.log",
    log_file_max_bytes: int = 10485760,  # 10MB
    log_file_backup_count: int = 5,
) -> None:
    """
    Configure structured logging for the application.

    Args:
        log_level: Logging level (DEBUG, INFO, WARNING, ERROR, CRITICAL)
        json_logs: If True, output logs in JSON format (recommended for production)
        log_to_file: If True, also write logs to file
        log_file_path: Path to log file
        log_file_max_bytes: Maximum size of log file before rotation
        log_file_backup_count: Number of backup files to keep
    """
    # Convert string log level to logging constant
    numeric_level = getattr(logging, log_level.upper(), logging.INFO)

    # Remove any existing handlers
    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.setLevel(numeric_level)

    # Console handler
    console_handler = logging.StreamHandler(sys.stdout)
    console_handler.setLevel(numeric_level)
    console_handler.setFormatter(logging.Formatter("%(message)s"))
    root_logger.addHandler(console_handler)

    # File handler (if enabled)
    if log_to_file:
        # Create log directory if it doesn't exist
        log_path = Path(log_file_path)
        log_path.parent.mkdir(parents=True, exist_ok=True)

        # Rotating file handler
        file_handler = logging.handlers.RotatingFileHandler(
            log_file_path,
            maxBytes=log_file_max_bytes,
            backupCount=log_file_backup_count,
            encoding="utf-8",
        )
        file_handler.setLevel(numeric_level)
        file_handler.setFormatter(logging.Formatter("%(message)s"))
        root_logger.addHandler(file_handler)

    # Define processors for structlog
    processors: list[Processor] = [
        structlog.contextvars.merge_contextvars,
        structlog.stdlib.add_log_level,
        structlog.stdlib.add_logger_name,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        structlog.processors.StackInfoRenderer(),
        add_app_context,
    ]

    if json_logs:
        # Production: JSON output for log aggregation systems
        processors.extend([
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ])
    else:
        # Development: Human-readable colored output
        processors.extend([
            structlog.processors.ExceptionRenderer(),
            structlog.dev.ConsoleRenderer(colors=True),
        ])

    # Configure structlog
    structlog.configure(
        processors=processors,
        wrapper_class=structlog.stdlib.BoundLogger,
        context_class=dict,
        logger_factory=structlog.stdlib.LoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str | None = None) -> structlog.stdlib.BoundLogger:
    """
    Get a structured logger instance.

    Args:
        name: Logger name (typically __name__ of the calling module)

    Returns:
        Configured structlog logger
    """
    return structlog.get_logger(name)


# Convenience function for masking sensitive data in logs
def mask_sensitive(value: str, visible_chars: int = 4) -> str:
    """
    Mask sensitive information for logging.

    Args:
        value: The sensitive string to mask
        visible_chars: Number of characters to show at the end

    Returns:
        Masked string (e.g., "***d123" for visible_chars=4)
    """
    if not value or len(value) <= visible_chars:
        return "***"
    return f"***{value[-visible_chars:]}"
