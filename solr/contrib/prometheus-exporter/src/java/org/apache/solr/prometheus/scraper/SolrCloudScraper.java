/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.prometheus.scraper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.impl.NoOpResponseParser;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.Pair;
import org.apache.solr.prometheus.collector.MetricSamples;
import org.apache.solr.prometheus.exporter.MetricsQuery;

public class SolrCloudScraper extends SolrScraper {

  private final CloudSolrClient solrClient;

  public SolrCloudScraper(CloudSolrClient solrClient, Executor executor) {
    super(executor);
    this.solrClient = solrClient;
  }

  @Override
  public Map<String, MetricSamples> pingAllCores(MetricsQuery query) throws IOException {
    Map<String, HttpSolrClient> httpSolrClients = createHttpSolrClients();

    Map<String, DocCollection> collectionState = solrClient.getClusterStateProvider().getClusterState().getCollectionsMap();

    List<Replica> replicas = collectionState.values()
        .stream()
        .map(DocCollection::getReplicas)
        .flatMap(List::stream)
        .collect(Collectors.toList());

    List<String> coreNames = replicas
        .stream()
        .map(Replica::getCoreName)
        .collect(Collectors.toList());

    Map<String, HttpSolrClient> coreToClient = replicas
        .stream()
        .map(replica -> new Pair<>(replica.getCoreName(), httpSolrClients.get(replica.getBaseUrl())))
        .collect(Collectors.toMap(Pair::first, Pair::second));

    Map<String, MetricSamples> samples = sendRequestsInParallel(coreNames, core -> {
      try {
        return request(coreToClient.get(core), query.withCore(core));
      } catch (IOException exception) {
        throw new RuntimeException(exception);
      }
    });

    closeAll(httpSolrClients);

    return samples;
  }

  @Override
  public Map<String, MetricSamples> pingAllCollections(MetricsQuery query) throws IOException {
    return sendRequestsInParallel(getCollections(), (collection) -> {
      try {
        return request(solrClient, query.withCollection(collection));
      } catch (IOException exception) {
        throw new RuntimeException(exception);
      }
    });
  }

  @Override
  public Map<String, MetricSamples> metricsForAllHosts(MetricsQuery query) throws IOException {
    Map<String, HttpSolrClient> httpSolrClients = createHttpSolrClients();

    Map<String, MetricSamples> samples = sendRequestsInParallel(httpSolrClients.keySet(), (baseUrl) -> {
      try {
        return request(httpSolrClients.get(baseUrl), query);
      } catch (IOException exception) {
        throw new RuntimeException(exception);
      }
    });

    closeAll(httpSolrClients);

    return samples;
  }

  @Override
  public MetricSamples search(MetricsQuery query) throws IOException {
    return request(solrClient, query);
  }

  @Override
  public MetricSamples collections(MetricsQuery metricsQuery) throws IOException {
    return request(solrClient, metricsQuery);
  }

  private void closeAll(Map<String, HttpSolrClient> clients) {
    clients.values().forEach(IOUtils::closeQuietly);
  }

  private Map<String, HttpSolrClient> createHttpSolrClients() throws IOException {
    Map<String, HttpSolrClient> solrClients = new HashMap<>();

    for (String baseUrl : getBaseUrls()) {
      NoOpResponseParser responseParser = new NoOpResponseParser();
      responseParser.setWriterType("json");

      HttpSolrClient.Builder builder = new HttpSolrClient.Builder();
      builder.withBaseSolrUrl(baseUrl);
      builder.withResponseParser(responseParser);

      HttpSolrClient httpSolrClient = builder.build();

      solrClients.put(baseUrl, httpSolrClient);
    }

    return solrClients;
  }

  private Set<String> getBaseUrls() throws IOException {
    return solrClient.getClusterStateProvider().getClusterState().getCollectionsMap().values()
        .stream()
        .map(DocCollection::getReplicas)
        .flatMap(List::stream)
        .map(Replica::getBaseUrl)
        .collect(Collectors.toSet());
  }

  private Set<String> getCollections() throws IOException {
    return solrClient.getClusterStateProvider().getClusterState().getCollectionsMap().keySet();
  }

  @Override
  public void close() {
    IOUtils.closeQuietly(solrClient);
  }
}
