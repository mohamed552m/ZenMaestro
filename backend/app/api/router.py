from fastapi import APIRouter

from app.api.routes import account, ai, health, plans, progress, reflections


api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(account.router)
api_router.include_router(ai.router)
api_router.include_router(plans.router)
api_router.include_router(reflections.router)
api_router.include_router(progress.router)
