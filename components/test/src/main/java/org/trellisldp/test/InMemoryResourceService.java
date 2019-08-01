/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.trellisldp.test;

import static java.time.Instant.now;
import static java.util.Collections.unmodifiableSet;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.trellisldp.api.Resource.SpecialResources.MISSING_RESOURCE;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Quad;
import org.apache.commons.rdf.api.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trellisldp.api.Metadata;
import org.trellisldp.api.Resource;
import org.trellisldp.api.ResourceService;
import org.trellisldp.api.TrellisUtils;
import org.trellisldp.vocabulary.LDP;

/**
 * A {@link ResourceService} that stores its contents in memory, for testing.
 */
public class InMemoryResourceService implements ResourceService {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryResourceService.class);

    private static final RDF rdfFactory = TrellisUtils.getInstance();

    private static final CompletableFuture<Void> DONE = completedFuture(null);

    private static final AtomicLong serviceCounter = new AtomicLong();

    private final long serviceNumber = serviceCounter.getAndIncrement();

    private final String ID_PREFIX = getClass().getSimpleName() + "-" + serviceNumber + ":";

    private final AtomicLong idCounter = new AtomicLong();

    private final Map<IRI, Resource> resources = new ConcurrentHashMap<>();

    private final Map<IRI, Dataset> auditData = new ConcurrentHashMap<>();

    private static final Set<IRI> SUPPORTED_IXN_MODELS;

    static {
        Set<IRI> models = new CopyOnWriteArraySet<>();
        models.add(LDP.RDFSource);
        models.add(LDP.NonRDFSource);
        models.add(LDP.Container);
        models.add(LDP.BasicContainer);
        SUPPORTED_IXN_MODELS = unmodifiableSet(models);
    }

    @Override
    public CompletionStage<? extends Resource> get(IRI identifier) {
        if (resources.containsKey(identifier)) {
            LOG.debug("Retrieving resource: {}", identifier);
            final Resource resource = resources.get(identifier);
            final Dataset auditQuads = auditData.getOrDefault(identifier, rdfFactory.createDataset());
            auditQuads.stream().peek(q -> LOG.debug("Retrieved audit tuple: {}", q)).forEach(resource.dataset()::add);

            return completedFuture(resource);
        }
        LOG.debug("Resource: {} not found.", identifier);
        return completedFuture(MISSING_RESOURCE);
    }

    @Override
    public CompletionStage<Void> replace(Metadata meta, Dataset data) {
        final IRI identifier = meta.getIdentifier();
        final IRI ixnModel = meta.getInteractionModel();
        final IRI container = meta.getContainer().orElse(null);
        final InMemoryResource newResource = new InMemoryResource(identifier, ixnModel, container, now(), data);
        resources.put(identifier, newResource);
        return DONE;
    }

    @Override
    public CompletionStage<Void> delete(Metadata metadata) {
        resources.remove(metadata.getIdentifier());
        return DONE;
    }

    @Override
    public CompletionStage<Void> add(IRI identifier, Dataset newData) {
        final Dataset oldData = auditData.computeIfAbsent(identifier, k -> rdfFactory.createDataset());
        newData.stream().peek(q -> LOG.debug("Received audit tuple: {}", q)).forEach(oldData::add);
        final Dataset refreshedData = auditData.get(identifier);
        refreshedData.stream().forEach(q -> LOG.debug("Recorded audit tuple: {}", q));
        return DONE;
    }

    @Override
    public CompletionStage<Void> touch(IRI identifier) {
        ((InMemoryResource) resources.get(identifier)).modified = now();
        return DONE;
    }

    @Override
    public Set<IRI> supportedInteractionModels() {
        return SUPPORTED_IXN_MODELS;
    }

    @Override
    public String generateIdentifier() {
        return ID_PREFIX + idCounter.getAndIncrement();
    }

    private static class InMemoryResource implements Resource {

        private final IRI identifier, ixnModel, container;

        private Instant modified;

        private final Dataset dataset;

        private InMemoryResource(IRI identifier, IRI ixnModel, IRI container, Instant modified, Dataset dataset) {
            this.identifier = identifier;
            this.ixnModel = ixnModel;
            this.container = container;
            this.modified = modified;
            this.dataset = dataset;
        }

        @Override
        public IRI getIdentifier() {
            return identifier;
        }

        @Override
        public IRI getInteractionModel() {
            return ixnModel;
        }

        @Override
        public Instant getModified() {
            return modified;
        }

        @Override
        public Optional<IRI> getContainer() {
            return Optional.ofNullable(container);
        }

        @SuppressWarnings("unchecked")
        @Override
        public Stream<Quad> stream() {
            return (Stream<Quad>) dataset.stream();
        }

        @Override
        public Dataset dataset() {
            return dataset;
        }
    }
}