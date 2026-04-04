package com.walmartlabs.concord.plugins.boxcutter.v2;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2025 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.walmartlabs.concord.client2.ApiClientFactory;
import com.walmartlabs.concord.plugins.boxcutter.BoxcutterParams;
import com.walmartlabs.concord.plugins.boxcutter.BoxcutterTaskCommon;
import com.walmartlabs.concord.runtime.v2.sdk.*;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.Map;

@Named("boxcutter")
@SuppressWarnings("unused")
public class BoxcutterTaskV2 implements ReentrantTask {

    private final ApiClientFactory apiClientFactory;
    private final Context context;

    @Inject
    public BoxcutterTaskV2(ApiClientFactory apiClientFactory, Context context) {
        this.apiClientFactory = apiClientFactory;
        this.context = context;
    }

    @Override
    public TaskResult execute(Variables input) throws Exception {
        Map<String, Object> merged = new HashMap<>(context.defaultVariables().toMap());
        merged.putAll(input.toMap());

        BoxcutterParams params = new BoxcutterParams(new MapBackedVariables(merged));
        return delegate().execute(params);
    }

    @Override
    public TaskResult resume(ResumeEvent event) throws Exception {
        return delegate().continueAfterSuspend(event.state());
    }

    private BoxcutterTaskCommon delegate() {
        String sessionToken = context.processConfiguration().processInfo().sessionToken();
        return new BoxcutterTaskCommon(
                sessionToken,
                apiClientFactory,
                context.processInstanceId());
    }
}
