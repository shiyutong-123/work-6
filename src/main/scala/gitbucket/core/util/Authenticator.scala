package gitbucket.core.util

import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService, SystemSettingsService}
import gitbucket.core.model.Role
import RepositoryService.RepositoryInfo
import Implicits._

/**
 * Shared lock for authenticators to prevent race conditions in high concurrency scenarios.
 */
private[util] object AuthenticatorLock {
  val lock = new Object()
}

/**
 * Allows only oneself and administrators.
 */
trait OneselfAuthenticator { self: ControllerBase & SystemSettingsService =>
  protected def oneselfOnly(action: => Any) = { authenticate(action) }
  protected def oneselfOnly[T](action: T => Any) = (form: T) => { authenticate(action(form)) }

  private def authenticate(action: => Any) = {
    withLockIfEnabled {
      context.loginAccount match {
        case Some(x) if x.isAdmin                      => action
        case Some(x) if request.paths(0) == x.userName => action
        case _                                         => Unauthorized()
      }
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}

/**
 * Allows only the repository owner and administrators.
 */
trait OwnerAuthenticator { self: ControllerBase & RepositoryService & AccountService & SystemSettingsService =>
  protected def ownerOnly(action: RepositoryInfo => Any) = { authenticate(action) }
  protected def ownerOnly[T](action: (T, RepositoryInfo) => Any) = (form: T) => { authenticate(action(form, _)) }

  private def authenticate(action: (RepositoryInfo) => Any) = {
    withLockIfEnabled {
      val userName = params("owner")
      val repoName = params("repository")
      getRepository(userName, repoName).map { repository =>
        context.loginAccount match {
          case Some(x) if x.isAdmin                      => action(repository)
          case Some(x) if repository.owner == x.userName => action(repository)
          // TODO Repository management is allowed for only group managers?
          case Some(x) if getGroupMembers(repository.owner).exists { m =>
                m.userName == x.userName && m.isManager
              } =>
            action(repository)
          case Some(x) if getCollaboratorUserNames(userName, repoName, Seq(Role.ADMIN)).contains(x.userName) =>
            action(repository)
          case _ => Unauthorized()
        }
      } getOrElse NotFound()
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}

/**
 * Allows only signed in users.
 */
trait UsersAuthenticator { self: ControllerBase & SystemSettingsService =>
  protected def usersOnly(action: => Any) = { authenticate(action) }
  protected def usersOnly[T](action: T => Any) = (form: T) => { authenticate(action(form)) }

  private def authenticate(action: => Any) = {
    withLockIfEnabled {
      context.loginAccount match {
        case Some(x) => action
        case None    => Unauthorized()
      }
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}

/**
 * Allows only administrators.
 */
trait AdminAuthenticator { self: ControllerBase & SystemSettingsService =>
  protected def adminOnly(action: => Any) = { authenticate(action) }
  protected def adminOnly[T](action: T => Any) = (form: T) => { authenticate(action(form)) }

  private def authenticate(action: => Any) = {
    withLockIfEnabled {
      context.loginAccount match {
        case Some(x) if (x.isAdmin) => action
        case _                      => Unauthorized()
      }
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}

/**
 * Allows only guests and signed in users who can access the repository.
 */
trait ReferrerAuthenticator { self: ControllerBase & RepositoryService & AccountService & SystemSettingsService =>
  protected def referrersOnly(action: RepositoryInfo => Any) = { authenticate(action) }
  protected def referrersOnly[T](action: (T, RepositoryInfo) => Any) = (form: T) => { authenticate(action(form, _)) }

  private def authenticate(action: RepositoryInfo => Any) = {
    withLockIfEnabled {
      val userName = params("owner")
      val repoName = params("repository")
      getRepository(userName, repoName).map { repository =>
        if (isReadable(repository.repository, context.loginAccount)) {
          action(repository)
        } else {
          Unauthorized()
        }
      } getOrElse NotFound()
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}

/**
 * Allows only signed in users who have read permission for the repository.
 */
trait ReadableUsersAuthenticator { self: ControllerBase & RepositoryService & AccountService & SystemSettingsService =>
  protected def readableUsersOnly(action: RepositoryInfo => Any) = { authenticate(action) }
  protected def readableUsersOnly[T](action: (T, RepositoryInfo) => Any) = (form: T) => {
    authenticate(action(form, _))
  }

  private def authenticate(action: RepositoryInfo => Any) = {
    withLockIfEnabled {
      val userName = params("owner")
      val repoName = params("repository")
      getRepository(userName, repoName).map { repository =>
        if (isReadable(repository.repository, context.loginAccount) || !repository.repository.isPrivate) {
          action(repository)
        } else {
          Unauthorized()
        }
      } getOrElse NotFound()
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}

/**
 * Allows only signed in users who have write permission for the repository.
 */
trait WritableUsersAuthenticator { self: ControllerBase & RepositoryService & AccountService & SystemSettingsService =>
  protected def writableUsersOnly(action: RepositoryInfo => Any) = { authenticate(action) }
  protected def writableUsersOnly[T](action: (T, RepositoryInfo) => Any) = (form: T) => {
    authenticate(action(form, _))
  }

  private def authenticate(action: RepositoryInfo => Any) = {
    withLockIfEnabled {
      val userName = params("owner")
      val repoName = params("repository")
      getRepository(userName, repoName).map { repository =>
        if (isWritable(repository.repository, context.loginAccount)) {
          action(repository)
        } else {
          Unauthorized()
        }
      } getOrElse NotFound()
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}

/**
 * Allows only the group managers.
 */
trait GroupManagerAuthenticator { self: ControllerBase & AccountService & SystemSettingsService =>
  protected def managersOnly(action: => Any) = { authenticate(action) }
  protected def managersOnly[T](action: T => Any) = (form: T) => { authenticate(action(form)) }

  private def authenticate(action: => Any) = {
    withLockIfEnabled {
      context.loginAccount match {
        case Some(x) if x.isAdmin                      => action
        case Some(x) if x.userName == request.paths(0) => action
        case Some(x) if getGroupMembers(request.paths(0)).exists { member =>
              member.userName == x.userName && member.isManager
            } =>
          action
        case _ => Unauthorized()
      }
    }
  }
  
  private def withLockIfEnabled[T](action: => T): T = {
    if (loadSystemSettings().basicBehavior.authenticatorLockEnabled) {
      AuthenticatorLock.lock.synchronized {
        action
      }
    } else {
      action
    }
  }
}
