use android::{ComponentName, ComponentState, PackageManager};

use crate::{ComponentRecord, Log};

/// Keeps a list of components disabled for every user, remembering the state of each
/// component before it was disabled so that it can be restored.
pub struct Components;

impl Components {
    /// Disables the components of `list` for every user and restores the components of
    /// `record` that are no longer in `list`. A component that is already disabled by
    /// someone else is left alone and not recorded, so it is never "restored" to enabled.
    /// Returns the new record.
    pub fn apply(list: &[String], record: &[ComponentRecord], log: &mut Log) -> Vec<ComponentRecord> {
        let users = match PackageManager::users() {
            Ok(users) if !users.is_empty() => users,
            Ok(_) | Err(_) => {
                log.error("cannot list the users; components are left unchanged");
                return record.to_vec();
            }
        };
        let mut next = Vec::new();
        for entry in record {
            if !users.contains(&entry.user) || list.contains(&entry.component) {
                continue;
            }
            let Some(component) = ComponentName::parse(&entry.component) else { continue };
            let state = ComponentState::from_name(&entry.state).unwrap_or(ComponentState::Default);
            match PackageManager::set_component_state(entry.user, &component, state) {
                Ok(()) => log.info(format!("restored {} for user {} ({})", entry.component, entry.user, entry.state)),
                Err(error) => {
                    log.error(format!("cannot restore {} for user {}: {error}", entry.component, entry.user));
                    next.push(entry.clone());
                }
            }
        }
        for user in &users {
            for name in list {
                let Some(component) = ComponentName::parse(name) else { continue };
                let known = record.iter().find(|entry| entry.user == *user && entry.component == *name);
                let state = match known {
                    Some(entry) => entry.state.clone(),
                    None => match PackageManager::component_state(*user, &component) {
                        Ok(ComponentState::Disabled) => {
                            log.info(format!("{name} is already disabled for user {user}; left alone"));
                            continue;
                        }
                        Ok(state) => state.name().to_string(),
                        Err(error) => {
                            log.warning(format!("cannot read the state of {name} for user {user}: {error}"));
                            continue;
                        }
                    },
                };
                match PackageManager::set_component_state(*user, &component, ComponentState::Disabled) {
                    Ok(()) => {
                        if known.is_none() {
                            log.info(format!("disabled {name} for user {user} (was {state})"));
                        }
                        next.push(ComponentRecord { user: *user, component: name.clone(), state });
                    }
                    Err(error) => {
                        log.warning(format!("cannot disable {name} for user {user}: {error}"));
                        if known.is_some() {
                            next.push(ComponentRecord { user: *user, component: name.clone(), state });
                        }
                    }
                }
            }
        }
        next.sort();
        next.dedup();
        next
    }
}
