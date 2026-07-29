"""Demo order API (SEEDED). Thin HTTP-ish handler that drives payment_service.

Present so every file referenced by the Sumo fixture and the C3 degraded-mode
example actually exists in the repo (grounding guarantee, R3 conflict fix).
"""
import logging

from payment_service import charge, MockGateway

logger = logging.getLogger("order_api")


def handle_charge(order):
    try:
        charge(order, MockGateway())
        return 200
    except ValueError as exc:
        # These two lines appear in the Sumo fixture as order_api entries.
        logger.error("unhandled ValueError: %s order=%s trace=payment_service.charge", exc, order["id"])
        logger.warning("returning 500 to client req=/v1/orders/%s/charge", order["id"])
        return 500
