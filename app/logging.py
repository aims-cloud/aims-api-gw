"""
Structured logging configuration using structlog.

Provides consistent, structured logging across the application with
JSON output for production environments and human-readable output for development.
"""
import logging
import sys
from typing import Any

import structlog
from structlog.types import EventDict, Processor


def add_app_context(logger: Any, method_name: str, event_dict: EventDict) -> EventDict:
    """Add application context to all log entries."""
    event_dict["app"] = "aims-api-gw"
    return event_dict


def configure_logging(log_level: str = "INFO", json_logs: bool = False) -> None:
    """
    Configure structured logging for the application.

    Args:
        log_level: Logging level (DEBUG, INFO, WARNING, ERROR, CRITICAL)
        json_logs: If True, output logs in JSON format (recommended for production)
    """
    # Convert string log level to logging constant
    numeric_level = getattr(logging, log_level.upper(), logging.INFO)

    # Configure standard library logging
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=numeric_level,
    )

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
