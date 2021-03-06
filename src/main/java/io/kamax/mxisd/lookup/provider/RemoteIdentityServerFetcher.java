/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2017 Maxime Dor
 *
 * https://max.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.lookup.provider;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.kamax.mxisd.controller.identity.v1.ClientBulkLookupRequest;
import io.kamax.mxisd.exception.InvalidResponseJsonException;
import io.kamax.mxisd.lookup.SingleLookupReply;
import io.kamax.mxisd.lookup.SingleLookupRequest;
import io.kamax.mxisd.lookup.ThreePidMapping;
import io.kamax.mxisd.lookup.fetcher.IRemoteIdentityServerFetcher;
import io.kamax.mxisd.matrix.IdentityServerUtils;
import io.kamax.mxisd.util.GsonParser;
import io.kamax.mxisd.util.RestClientUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Scope("prototype")
@Lazy
public class RemoteIdentityServerFetcher implements IRemoteIdentityServerFetcher {

    private Logger log = LoggerFactory.getLogger(RemoteIdentityServerFetcher.class);

    private Gson gson = new Gson();
    private GsonParser parser = new GsonParser(gson);

    @Override
    public boolean isUsable(String remote) {
        return IdentityServerUtils.isUsable(remote);
    }

    @Override
    public Optional<SingleLookupReply> find(String remote, SingleLookupRequest request) {
        log.info("Looking up {} 3PID {} using {}", request.getType(), request.getThreePid(), remote);

        try {
            HttpURLConnection rootSrvConn = (HttpURLConnection) new URL(
                    remote + "/_matrix/identity/api/v1/lookup?medium=" + request.getType() + "&address=" + request.getThreePid()
            ).openConnection();
            JsonObject obj = parser.parse(rootSrvConn.getInputStream());
            if (obj.has("address")) {
                log.info("Found 3PID mapping: {}", gson.toJson(obj));

                return Optional.of(SingleLookupReply.fromRecursive(request, gson.toJson(obj)));
            }

            log.info("Empty 3PID mapping from {}", remote);
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Error looking up 3PID mapping {}: {}", request.getThreePid(), e.getMessage());
            return Optional.empty();
        } catch (JsonParseException e) {
            log.warn("Invalid JSON answer from {}", remote);
            return Optional.empty();
        }
    }

    @Override
    public List<ThreePidMapping> find(String remote, List<ThreePidMapping> mappings) {
        List<ThreePidMapping> mappingsFound = new ArrayList<>();

        ClientBulkLookupRequest mappingRequest = new ClientBulkLookupRequest();
        mappingRequest.setMappings(mappings);

        String url = remote + "/_matrix/identity/api/v1/bulk_lookup";
        CloseableHttpClient client = HttpClients.createDefault();
        try {
            HttpPost request = RestClientUtils.post(url, mappingRequest);
            try (CloseableHttpResponse response = client.execute(request)) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    log.info("Could not perform lookup at {} due to HTTP return code: {}", url, response.getStatusLine().getStatusCode());
                    return mappingsFound;
                }

                ClientBulkLookupRequest input = parser.parse(response, ClientBulkLookupRequest.class);
                for (List<String> mappingRaw : input.getThreepids()) {
                    ThreePidMapping mapping = new ThreePidMapping();
                    mapping.setMedium(mappingRaw.get(0));
                    mapping.setValue(mappingRaw.get(1));
                    mapping.setMxid(mappingRaw.get(2));
                    mappingsFound.add(mapping);
                }
            }
        } catch (IOException e) {
            log.warn("Unable to fetch remote lookup data: {}", e.getMessage());
        } catch (InvalidResponseJsonException e) {
            log.info("HTTP response from {} was empty/invalid", remote);
        }

        return mappingsFound;
    }

}
