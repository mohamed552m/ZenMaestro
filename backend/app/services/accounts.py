from sqlalchemy.orm import Session

from app.api.dependencies.auth import AuthenticatedUser
from app.models.user import User


def ensure_database_user(session: Session, identity: AuthenticatedUser) -> User:
    user = session.get(User, identity.uid)
    if user is None:
        user = User(
            uid=identity.uid,
            email=identity.email,
            display_name=identity.name,
        )
        session.add(user)
        session.flush()
        return user

    changed = False
    if identity.email is not None and user.email != identity.email:
        user.email = identity.email
        changed = True
    if identity.name is not None and user.display_name != identity.name:
        user.display_name = identity.name
        changed = True
    if changed:
        session.flush()
    return user
