// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.singleusergroup;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.data.GroupDescription;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AbstractGroupBackend;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.query.QueryParseException;
import com.google.gerrit.server.query.account.AccountQueryBuilder;
import com.google.gerrit.server.query.account.AccountQueryProcessor;
import com.google.gwtorm.server.OrmException;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes a group out of each user.
 *
 * <p>UUIDs for the groups are derived from the unique username attached to the account. A user can
 * only be used as a group if it has a username.
 */
@Singleton
public class SingleUserGroup extends AbstractGroupBackend {
  private static final Logger log = LoggerFactory.getLogger(SingleUserGroup.class);

  private static final String UUID_PREFIX = "user:";
  private static final String NAME_PREFIX = "user/";
  private static final String ACCOUNT_PREFIX = "userid/";
  private static final String ACCOUNT_ID_PATTERN = "[1-9][0-9]*";
  private static final int MAX = 10;

  public static class Module extends AbstractModule {
    @Override
    protected void configure() {
      DynamicSet.bind(binder(), GroupBackend.class).to(SingleUserGroup.class);
    }
  }

  private final AccountCache accountCache;
  private final AccountQueryBuilder queryBuilder;
  private final AccountQueryProcessor queryProcessor;

  @Inject
  SingleUserGroup(
      AccountCache accountCache,
      AccountQueryBuilder queryBuilder,
      AccountQueryProcessor queryProcessor) {
    this.accountCache = accountCache;
    this.queryBuilder = queryBuilder;
    this.queryProcessor = queryProcessor;
  }

  @Override
  public boolean handles(AccountGroup.UUID uuid) {
    return uuid.get().startsWith(UUID_PREFIX);
  }

  @Override
  public GroupMembership membershipsOf(final IdentifiedUser user) {
    ImmutableList.Builder<AccountGroup.UUID> groups = ImmutableList.builder();
    groups.add(uuid(user.getAccountId()));
    if (user.getUserName() != null) {
      groups.add(uuid(user.getUserName()));
    }
    return new ListGroupMembership(groups.build());
  }

  @Override
  public GroupDescription.Basic get(final AccountGroup.UUID uuid) {
    String ident = username(uuid);
    AccountState state;
    if (ident.matches(ACCOUNT_ID_PATTERN)) {
      state = accountCache.get(new Account.Id(Integer.parseInt(ident)));
    } else if (ident.matches(Account.USER_NAME_PATTERN)) {
      state = accountCache.getByUsername(ident);
    } else {
      return null;
    }
    if (state != null) {
      final String name = nameOf(uuid, state);
      final String email = Strings.emptyToNull(state.getAccount().getPreferredEmail());
      return new GroupDescription.Basic() {
        @Override
        public AccountGroup.UUID getGroupUUID() {
          return uuid;
        }

        @Override
        public String getName() {
          return name;
        }

        @Override
        @Nullable
        public String getEmailAddress() {
          return email;
        }

        @Override
        @Nullable
        public String getUrl() {
          return null;
        }
      };
    }
    return null;
  }

  @Override
  public Collection<GroupReference> suggest(String name, @Nullable ProjectControl project) {
    try {
      return Lists.transform(
          queryProcessor.setLimit(MAX).query(queryBuilder.defaultQuery(name)).entities(),
          new Function<AccountState, GroupReference>() {
            @Override
            public GroupReference apply(AccountState state) {
              AccountGroup.UUID uuid;
              if (state.getUserName() != null) {
                uuid = uuid(state.getUserName());
              } else {
                uuid = uuid(state.getAccount().getId());
              }
              return new GroupReference(uuid, nameOf(uuid, state));
            }
          });
    } catch (OrmException | QueryParseException err) {
      log.warn("Cannot suggest users", err);
      return Collections.emptyList();
    }
  }

  private static String username(AccountGroup.UUID uuid) {
    checkUUID(uuid);
    return uuid.get().substring(UUID_PREFIX.length());
  }

  private static AccountGroup.UUID uuid(Account.Id ident) {
    return uuid(Integer.toString(ident.get()));
  }

  private static AccountGroup.UUID uuid(String username) {
    return new AccountGroup.UUID(UUID_PREFIX + username);
  }

  private static void checkUUID(AccountGroup.UUID uuid) {
    checkArgument(
        uuid.get().startsWith(UUID_PREFIX), "SingleUserGroup does not handle %s", uuid.get());
  }

  private static String nameOf(AccountGroup.UUID uuid, AccountState account) {
    StringBuilder buf = new StringBuilder();
    if (account.getAccount().getFullName() != null) {
      buf.append(account.getAccount().getFullName());
    }
    if (account.getUserName() != null) {
      if (buf.length() > 0) {
        buf.append(" (").append(account.getUserName()).append(")");
      } else {
        buf.append(account.getUserName());
      }
    } else if (buf.length() > 0) {
      buf.append(" (").append(account.getAccount().getId().get()).append(")");
    } else {
      buf.append(account.getAccount().getId().get());
    }

    String ident = username(uuid);
    if (ident.matches(ACCOUNT_ID_PATTERN)) {
      buf.insert(0, ACCOUNT_PREFIX);
    } else {
      buf.insert(0, NAME_PREFIX);
    }
    return buf.toString();
  }
}
