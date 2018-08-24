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
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Arrays.asList;
import static java.util.Optional.of;
import static java.util.function.Predicate.isEqual;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.generate;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.trellisldp.api.RDFUtils.TRELLIS_BNODE_PREFIX;
import static org.trellisldp.api.RDFUtils.TRELLIS_DATA_PREFIX;
import static org.trellisldp.api.RDFUtils.getInstance;
import static org.trellisldp.api.Resource.SpecialResources.DELETED_RESOURCE;
import static org.trellisldp.api.Resource.SpecialResources.MISSING_RESOURCE;
import static org.trellisldp.vocabulary.RDF.type;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.trellisldp.api.Binary;
import org.trellisldp.api.Resource;
import org.trellisldp.api.ResourceService;
import org.trellisldp.api.Session;
import org.trellisldp.vocabulary.AS;
import org.trellisldp.vocabulary.DC;
import org.trellisldp.vocabulary.FOAF;
import org.trellisldp.vocabulary.LDP;
import org.trellisldp.vocabulary.PROV;
import org.trellisldp.vocabulary.SKOS;
import org.trellisldp.vocabulary.Trellis;
import org.trellisldp.vocabulary.XSD;

/**
 * Resource Service tests.
 */
@TestInstance(PER_CLASS)
public interface ResourceServiceTests {

    String SUBJECT0 = "http://example.com/subject/0";

    String SUBJECT1 = "http://example.com/subject/1";

    String SUBJECT2 = "http://example.com/subject/2";

    IRI ROOT_CONTAINER = getInstance().createIRI(TRELLIS_DATA_PREFIX);

    /**
     * Get the resource service implementation.
     * @return the resource service implementation
     */
    ResourceService getResourceService();

    /**
     * Get a Session for the operation(s).
     * @return a session
     */
    Session getSession();

    /**
     * Test creating a resource.
     * @throws Exception if the operation failed
     */
    @Test
    @DisplayName("Test creating resource")
    default void testCreateResource() throws Exception {
        final RDF rdf = getInstance();
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset = buildDataset(identifier, "Creation Test", SUBJECT1);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.RDFSource, dataset, ROOT_CONTAINER, null)
                .join());
        final Resource res = getResourceService().get(identifier).join();
        res.stream(Trellis.PreferUserManaged)
           .forEach(t -> assertTrue(dataset.contains(of(Trellis.PreferUserManaged), t.getSubject(), t.getPredicate(),
                        t.getObject())));
    }

    /**
     * Test replacing a resource.
     * @throws Exception if the operation failed
     */
    @Test
    @DisplayName("Test replacing resource")
    default void testReplaceResource() throws Exception {
        final RDF rdf = getInstance();
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset = buildDataset(identifier, "Replacement Test", SUBJECT2);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.RDFSource, dataset, ROOT_CONTAINER, null)
                .join());

        dataset.clear();
        dataset.add(Trellis.PreferUserManaged, identifier, SKOS.prefLabel, rdf.createLiteral("preferred label"));
        dataset.add(Trellis.PreferUserManaged, identifier, SKOS.altLabel, rdf.createLiteral("alternate label"));
        dataset.add(Trellis.PreferUserManaged, identifier, type, SKOS.Concept);

        assertNull(getResourceService().replace(identifier, getSession(), LDP.RDFSource, dataset, ROOT_CONTAINER, null)
                    .join());
        final Resource res = getResourceService().get(identifier).join();
        res.stream(Trellis.PreferUserManaged).forEach(t ->
                    assertTrue(dataset.contains(of(Trellis.PreferUserManaged), t.getSubject(), t.getPredicate(),
                        t.getObject())));
        assertEquals(3L, res.stream(Trellis.PreferUserManaged).count());
    }

    /**
     * Test deleting a resource.
     * @throws Exception if the operation failed
     */
    @Test
    @DisplayName("Test deleting resource")
    default void testDeleteResource() throws Exception {
        final RDF rdf = getInstance();
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset = buildDataset(identifier, "Deletion Test", SUBJECT1);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());

        assertNull(getResourceService().create(identifier, getSession(), LDP.RDFSource, dataset, ROOT_CONTAINER, null)
                .join());
        assertNotEquals(DELETED_RESOURCE, getResourceService().get(identifier).join());

        assertNull(getResourceService().delete(identifier, getSession(), LDP.Resource, rdf.createDataset()).join());
        assertEquals(DELETED_RESOURCE, getResourceService().get(identifier).join());
    }

    /**
     * Test adding immutable data.
     * @throws Exception if the operation failed
     */
    @Test
    @DisplayName("Test adding immutable data")
    default void testAddImmutableData() throws Exception {
        final RDF rdf = getInstance();
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset0 = buildDataset(identifier, "Immutable Resource Test", SUBJECT2);

        assertNull(getResourceService().create(identifier, getSession(), LDP.RDFSource, dataset0, ROOT_CONTAINER, null)
                .join());

        final IRI audit1 = rdf.createIRI(TRELLIS_BNODE_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset1 = rdf.createDataset();
        dataset1.add(Trellis.PreferAudit, identifier, PROV.wasGeneratedBy, audit1);
        dataset1.add(Trellis.PreferAudit, audit1, type, PROV.Activity);
        dataset1.add(Trellis.PreferAudit, audit1, type, AS.Create);
        dataset1.add(Trellis.PreferAudit, audit1, PROV.atTime, rdf.createLiteral(now().toString(), XSD.dateTime));

        assertNull(getResourceService().add(identifier, getSession(), dataset1).join());

        final Resource res = getResourceService().get(identifier).join();
        res.stream(Trellis.PreferAudit).forEach(t ->
                assertTrue(dataset1.contains(of(Trellis.PreferAudit), t.getSubject(), t.getPredicate(),
                        t.getObject())));
        assertEquals(4L, res.stream(Trellis.PreferAudit).count());

        final IRI audit2 = rdf.createIRI(TRELLIS_BNODE_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset2 = rdf.createDataset();
        dataset2.add(Trellis.PreferAudit, identifier, PROV.wasGeneratedBy, audit2);
        dataset2.add(Trellis.PreferAudit, audit2, type, PROV.Activity);
        dataset2.add(Trellis.PreferAudit, audit2, type, AS.Update);
        dataset2.add(Trellis.PreferAudit, audit2, PROV.atTime, rdf.createLiteral(now().toString(), XSD.dateTime));

        assertNull(getResourceService().add(identifier, getSession(), dataset2).join());

        final Resource res2 = getResourceService().get(identifier).join();

        final Dataset combined = rdf.createDataset();
        dataset1.stream().forEach(combined::add);
        dataset2.stream().forEach(combined::add);

        res2.stream(Trellis.PreferAudit).map(t -> rdf.createQuad(Trellis.PreferAudit, t.getSubject(),
                    t.getPredicate(), t.getObject())).forEach(q -> assertTrue(combined.contains(q)));
        assertEquals(8L, res2.stream(Trellis.PreferAudit).count());
    }

    /**
     * Test an LDP:RDFSource.
     * @throws Exception if the create operation fails
     */
    @Test
    @DisplayName("Test LDP-RS")
    default void testLdpRs() throws Exception {
        final Instant time = now();
        final RDF rdf = getInstance();
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset = buildDataset(identifier, "Create LDP-RS Test", SUBJECT1);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.RDFSource, dataset, ROOT_CONTAINER, null)
                .join());
        final Resource res = getResourceService().get(identifier).join();
        assertAll(checkResource(res, identifier, LDP.RDFSource, time, dataset));
        assertEquals(3L, res.stream(Trellis.PreferUserManaged).count());
    }

    /**
     * Test an LDP:NonRDFSource.
     * @throws Exception if the create operation fails
     */
    @Test
    @DisplayName("Test LDP-NR")
    default void testLdpNr() throws Exception {
        final Instant time = now();
        final RDF rdf = getInstance();
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier());
        final Dataset dataset = buildDataset(identifier, "Create LDP-NR Test", SUBJECT2);

        final Instant binaryTime = now();
        final IRI binaryLocation = rdf.createIRI("binary:location/" + getResourceService().generateIdentifier());
        final Binary binary = new Binary(binaryLocation, binaryTime, "text/plain", 150L);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.NonRDFSource, dataset, ROOT_CONTAINER,
                    binary).join());
        final Resource res = getResourceService().get(identifier).join();
        assertAll(checkResource(res, identifier, LDP.NonRDFSource, time, dataset));
        assertEquals(3L, res.stream(Trellis.PreferUserManaged).count());
        res.getBinary().ifPresent(b -> {
            assertEquals(binaryLocation, b.getIdentifier());
            assertFalse(b.getModified().isBefore(binaryTime.truncatedTo(MILLIS)));
            assertFalse(b.getModified().isAfter(now()));
            assertEquals(of("text/plain"), b.getMimeType());
            assertEquals(of(150L), b.getSize());
        });
    }

    /**
     * Test an LDP:Container.
     * @throws Exception if the create operation fails
     */
    @Test
    @DisplayName("Test LDP-C")
    default void testLdpC() throws Exception {
        final Instant time = now();
        final RDF rdf = getInstance();
        final String base = TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier();
        final IRI identifier = rdf.createIRI(base);
        final Dataset dataset0 = buildDataset(identifier, "Container Test", SUBJECT0);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.Container, dataset0, ROOT_CONTAINER, null)
                .join());

        final IRI child1 = rdf.createIRI(base + "/child01");
        final Dataset dataset1 = buildDataset(child1, "Contained Child 1", SUBJECT1);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child1).join());
        assertNull(getResourceService().create(child1, getSession(), LDP.RDFSource, dataset1, identifier, null).join());

        final IRI child2 = rdf.createIRI(base + "/child02");
        final Dataset dataset2 = buildDataset(child2, "Contained Child2", SUBJECT2);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child2).join());
        assertNull(getResourceService().create(child2, getSession(), LDP.RDFSource, dataset2, identifier, null).join());

        final Resource res = getResourceService().get(identifier).join();
        assertAll(checkResource(res, identifier, LDP.Container, time, dataset0));
        assertEquals(2L, res.stream(LDP.PreferContainment).count());
        final Graph graph = rdf.createGraph();
        res.stream(LDP.PreferContainment).forEach(graph::add);
        assertTrue(graph.contains(identifier, LDP.contains, child1));
        assertTrue(graph.contains(identifier, LDP.contains, child2));
        assertEquals(3L, res.stream(Trellis.PreferUserManaged).count());
    }

    /**
     * Test an LDP:BasicContainer.
     * @throws Exception if the create operation fails
     */
    @Test
    @DisplayName("Test LDP-BC")
    default void testLdpBC() throws Exception {
        final Instant time = now();
        final RDF rdf = getInstance();
        final String base = TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier();
        final IRI identifier = rdf.createIRI(base);
        final Dataset dataset0 = buildDataset(identifier, "Basic Container Test", SUBJECT0);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.BasicContainer, dataset0, ROOT_CONTAINER,
                    null).join());

        final IRI child1 = rdf.createIRI(base + "/child11");
        final Dataset dataset1 = buildDataset(child1, "Contained Child 1", SUBJECT1);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child1).join());
        assertNull(getResourceService().create(child1, getSession(), LDP.RDFSource, dataset1, identifier, null).join());

        final IRI child2 = rdf.createIRI(base + "/child12");
        final Dataset dataset2 = buildDataset(child2, "Contained Child2", SUBJECT2);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child2).join());
        assertNull(getResourceService().create(child2, getSession(), LDP.RDFSource, dataset2, identifier, null).join());

        final Resource res = getResourceService().get(identifier).join();
        assertAll(checkResource(res, identifier, LDP.BasicContainer, time, dataset0));
        assertEquals(2L, res.stream(LDP.PreferContainment).count());
        final Graph graph = rdf.createGraph();
        res.stream(LDP.PreferContainment).forEach(graph::add);
        assertTrue(graph.contains(identifier, LDP.contains, child1));
        assertTrue(graph.contains(identifier, LDP.contains, child2));
        assertEquals(3L, res.stream(Trellis.PreferUserManaged).count());
    }

    /**
     * Test an LDP:DirectContainer.
     * @throws Exception if the create operation fails
     */
    @Test
    @DisplayName("Test LDP-DC")
    default void testLdpDC() throws Exception {
        // Only test DC if the backend supports it
        assumeTrue(getResourceService().supportedInteractionModels().contains(LDP.DirectContainer));

        final Instant time = now();
        final RDF rdf = getInstance();
        final String base = TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier();
        final IRI identifier = rdf.createIRI(base);
        final IRI member = rdf.createIRI(base + "/member");
        final Dataset dataset0 = buildDataset(identifier, "Direct Container Test", SUBJECT0);
        dataset0.add(Trellis.PreferUserManaged, identifier, LDP.membershipResource, member);
        dataset0.add(Trellis.PreferUserManaged, identifier, LDP.isMemberOfRelation, DC.isPartOf);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.DirectContainer, dataset0, ROOT_CONTAINER,
                    null).join());

        final IRI child1 = rdf.createIRI(base + "/child1");
        final Dataset dataset1 = buildDataset(child1, "Child 1", SUBJECT1);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child1).join());
        assertNull(getResourceService().create(child1, getSession(), LDP.RDFSource, dataset1, identifier, null).join());

        final IRI child2 = rdf.createIRI(base + "/child2");
        final Dataset dataset2 = buildDataset(child2, "Child 2", SUBJECT2);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child2).join());
        assertNull(getResourceService().create(child2, getSession(), LDP.RDFSource, dataset2, identifier, null).join());

        final Resource res = getResourceService().get(identifier).join();
        assertAll(checkResource(res, identifier, LDP.DirectContainer, time, dataset0));
        assertEquals(of(member), res.getMembershipResource());
        assertEquals(of(DC.isPartOf), res.getMemberOfRelation());
        assertFalse(res.getMemberRelation().isPresent());
        assertFalse(res.getInsertedContentRelation().filter(isEqual(LDP.MemberSubject).negate()).isPresent());
        assertEquals(2L, res.stream(LDP.PreferContainment).count());
        final Graph graph = rdf.createGraph();
        res.stream(LDP.PreferContainment).forEach(graph::add);
        assertTrue(graph.contains(identifier, LDP.contains, child1));
        assertTrue(graph.contains(identifier, LDP.contains, child2));
        assertEquals(5L, res.stream(Trellis.PreferUserManaged).count());
    }

    /**
     * Test an LDP:IndirectContainer.
     * @throws Exception if the create operation fails
     */
    @Test
    @DisplayName("Test LDP-IC")
    default void testLdpIC() throws Exception {
        // Only execute this test if the backend supports it
        assumeTrue(getResourceService().supportedInteractionModels().contains(LDP.IndirectContainer));

        final Instant time = now();
        final RDF rdf = getInstance();
        final String base = TRELLIS_DATA_PREFIX + getResourceService().generateIdentifier();
        final IRI identifier = rdf.createIRI(base);
        final IRI member = rdf.createIRI(base + "/member");
        final Dataset dataset0 = buildDataset(identifier, "Indirect Container Test", SUBJECT0);
        dataset0.add(Trellis.PreferUserManaged, identifier, LDP.membershipResource, member);
        dataset0.add(Trellis.PreferUserManaged, identifier, LDP.hasMemberRelation, DC.relation);
        dataset0.add(Trellis.PreferUserManaged, identifier, LDP.insertedContentRelation, FOAF.primaryTopic);

        assertEquals(MISSING_RESOURCE, getResourceService().get(identifier).join());
        assertNull(getResourceService().create(identifier, getSession(), LDP.IndirectContainer, dataset0,
                    ROOT_CONTAINER, null).join());

        final IRI child1 = rdf.createIRI(base + "/child1");
        final Dataset dataset1 = buildDataset(child1, "Indirect Container Child 1", SUBJECT1);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child1).join());
        assertNull(getResourceService().create(child1, getSession(), LDP.RDFSource, dataset1, identifier, null).join());

        final IRI child2 = rdf.createIRI(base + "/child2");
        final Dataset dataset2 = buildDataset(child2, "Indirect Container Child 2", SUBJECT2);

        assertEquals(MISSING_RESOURCE, getResourceService().get(child2).join());
        assertNull(getResourceService().create(child2, getSession(), LDP.RDFSource, dataset2, identifier, null).join());

        final Resource res = getResourceService().get(identifier).join();
        assertAll(checkResource(res, identifier, LDP.IndirectContainer, time, dataset0));
        assertEquals(of(member), res.getMembershipResource());
        assertEquals(of(DC.relation), res.getMemberRelation());
        assertEquals(of(FOAF.primaryTopic), res.getInsertedContentRelation());
        assertFalse(res.getMemberOfRelation().isPresent());
        assertEquals(2L, res.stream(LDP.PreferContainment).count());
        final Graph graph = rdf.createGraph();
        res.stream(LDP.PreferContainment).forEach(graph::add);
        assertTrue(graph.contains(identifier, LDP.contains, child1));
        assertTrue(graph.contains(identifier, LDP.contains, child2));
        assertEquals(6L, res.stream(Trellis.PreferUserManaged).count());
    }

    /**
     * Test identifier generation.
     */
    default void testIdentifierGeneration() {
        final int size = 1000;
        final Set<String> identifiers = generate(getResourceService()::generateIdentifier).limit(size).collect(toSet());
        assertEquals(size, identifiers.size());
    }

    /**
     * Check a Trellis Resource.
     * @param res the Resource
     * @param identifier the identifier
     * @param ldpType the LDP type
     * @param time an instant before which the resource shouldn't exist
     * @param dataset a dataset to compare against
     * @return a stream of testable assertions
     */
    default Stream<Executable> checkResource(final Resource res, final IRI identifier, final IRI ldpType,
            final Instant time, final Dataset dataset) {
        return Stream.of(
                () -> assertEquals(ldpType, res.getInteractionModel(), "Check the interaction model"),
                () -> assertEquals(identifier, res.getIdentifier(), "Check the identifier"),
                () -> assertFalse(res.getModified().isBefore(time), "Check the modification time (1)"),
                () -> assertFalse(res.getModified().isAfter(now()), "Check the modification time (2)"),
                () -> assertFalse(res.hasAcl(), "Check for an ACL"),
                () -> assertEquals(LDP.NonRDFSource.equals(ldpType), res.getBinary().isPresent(), "Check Binary"),
                () -> assertEquals(asList(LDP.DirectContainer, LDP.IndirectContainer).contains(ldpType),
                       res.getMembershipResource().isPresent(), "Check ldp:membershipResource"),
                () -> assertEquals(asList(LDP.DirectContainer, LDP.IndirectContainer).contains(ldpType),
                       res.getMemberRelation().isPresent() || res.getMemberOfRelation().isPresent(),
                       "Check ldp:hasMemberRelation or ldp:isMemberOfRelation"),
                () -> assertEquals(asList(LDP.DirectContainer, LDP.IndirectContainer).contains(ldpType),
                       res.getInsertedContentRelation().isPresent(), "Check ldp:insertedContentRelation"),
                () -> res.stream(Trellis.PreferUserManaged).forEach(t ->
                        assertTrue(dataset.contains(of(Trellis.PreferUserManaged), t.getSubject(), t.getPredicate(),
                                t.getObject()))));
    }

    /**
     * Build a dataset.
     * @param resource the resource IRI
     * @param title a title
     * @param subject a subject
     * @return a new dataset
     */
    default Dataset buildDataset(final IRI resource, final String title, final String subject) {
        final Dataset dataset = getInstance().createDataset();
        dataset.add(Trellis.PreferUserManaged, resource, DC.title, getInstance().createLiteral(title));
        dataset.add(Trellis.PreferUserManaged, resource, DC.subject, getInstance().createIRI(subject));
        dataset.add(Trellis.PreferUserManaged, resource, type, SKOS.Concept);
        return dataset;
    }
}
