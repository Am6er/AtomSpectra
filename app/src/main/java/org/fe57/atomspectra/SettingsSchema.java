package org.fe57.atomspectra;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The settings of one device type: an ordered list of categories, each holding descriptors.
 */
public final class SettingsSchema {
    public static final SettingsSchema EMPTY = new SettingsSchema(Collections.emptyList());

    public static final class Category {
        @StringRes
        public final int titleRes;
        public final List<SettingDescriptor> settings;

        Category(int titleRes, List<SettingDescriptor> settings) {
            this.titleRes = titleRes;
            this.settings = Collections.unmodifiableList(settings);
        }
    }

    public final List<Category> categories;

    private SettingsSchema(List<Category> categories) {
        this.categories = Collections.unmodifiableList(categories);
    }

    public boolean isEmpty() {
        return categories.isEmpty();
    }

    public List<SettingDescriptor> all() {
        List<SettingDescriptor> all = new ArrayList<>();
        for (Category category : categories) all.addAll(category.settings);
        return all;
    }

    @Nullable
    public SettingDescriptor find(String key) {
        for (Category category : categories) {
            for (SettingDescriptor descriptor : category.settings) {
                if (descriptor.key.equals(key)) return descriptor;
            }
        }
        return null;
    }

    public boolean hasScope(SettingDescriptor.Scope scope) {
        for (SettingDescriptor descriptor : all()) {
            if (descriptor.scope() == scope) return true;
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Category> categories = new ArrayList<>();

        public Builder category(@StringRes int titleRes, SettingDescriptor... settings) {
            categories.add(new Category(titleRes, new ArrayList<>(Arrays.asList(settings))));
            return this;
        }

        public SettingsSchema build() {
            return new SettingsSchema(new ArrayList<>(categories));
        }
    }
}
