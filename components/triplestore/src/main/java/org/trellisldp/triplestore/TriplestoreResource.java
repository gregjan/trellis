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
package org.trellisldp.triplestore;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.function.Predicate.isEqual;
import static java.util.stream.Stream.builder;
import static java.util.stream.Stream.concat;
import static java.util.stream.Stream.of;
import static org.apache.jena.graph.NodeFactory.createURI;
import static org.apache.jena.graph.Triple.create;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.api.Resource.SpecialResources.DELETED_RESOURCE;
import static org.trellisldp.api.Resource.SpecialResources.MISSING_RESOURCE;
import static org.trellisldp.triplestore.TriplestoreUtils.OBJECT;
import static org.trellisldp.triplestore.TriplestoreUtils.PREDICATE;
import static org.trellisldp.triplestore.TriplestoreUtils.SUBJECT;
import static org.trellisldp.triplestore.TriplestoreUtils.getInstance;
import static org.trellisldp.triplestore.TriplestoreUtils.getObject;
import static org.trellisldp.triplestore.TriplestoreUtils.getPredicate;
import static org.trellisldp.triplestore.TriplestoreUtils.getSubject;
import static org.trellisldp.triplestore.TriplestoreUtils.nodesToTriple;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Literal;
import org.apache.commons.rdf.api.Quad;
import org.apache.commons.rdf.api.RDFTerm;
import org.apache.commons.rdf.jena.JenaRDF;
import org.apache.jena.query.Query;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementPathBlock;
import org.slf4j.Logger;
import org.trellisldp.api.BinaryMetadata;
import org.trellisldp.api.Resource;
import org.trellisldp.vocabulary.DC;
import org.trellisldp.vocabulary.LDP;
import org.trellisldp.vocabulary.RDF;
import org.trellisldp.vocabulary.Trellis;

/**
 * A triplestore-based implementation of the Trellis Resource API.
 */
public class TriplestoreResource implements Resource {

    private static final Logger LOGGER = getLogger(TriplestoreResource.class);
    private static final JenaRDF rdf = getInstance();

    private final IRI identifier;
    private final RDFConnection rdfConnection;
    private final boolean includeLdpType;
    private final Map<IRI, RDFTerm> data = new HashMap<>();
    private final Map<IRI, Supplier<Stream<Quad>>> graphMapper = new HashMap<>();

    /**
     * Create a Triplestore-based Resource.
     * @param rdfConnection the triplestore connector
     * @param identifier the identifier
     * @param includeLdpType whether to include the LDP interaction model in the response
     */
    public TriplestoreResource(final RDFConnection rdfConnection, final IRI identifier, final boolean includeLdpType) {
        this.identifier = identifier;
        this.rdfConnection = rdfConnection;
        this.includeLdpType = includeLdpType;
        graphMapper.put(Trellis.PreferUserManaged, this::fetchUserQuads);
        graphMapper.put(Trellis.PreferAudit, this::fetchAuditQuads);
        graphMapper.put(Trellis.PreferAccessControl, this::fetchAclQuads);
        graphMapper.put(LDP.PreferContainment, this::fetchContainmentQuads);
        graphMapper.put(LDP.PreferMembership, this::fetchMembershipQuads);
    }

    /**
     * Try to load a Trellis resource.
     *
     * @implSpec This method will load a {@link Resource}, initializing the object with all resource metadata
     *           used with {@link #getModified}, {@link #getInteractionModel} and other data fetched by the accessors.
     *           The resource content is fetched on demand via the {@link #stream} method.
     * @param rdfConnection the triplestore connector
     * @param identifier the identifier
     * @param includeLdpType whether to include the LDP type in the body of the RDF
     * @return a new completion stage with a {@link Resource}, if one exists
     */
    public static CompletableFuture<Resource> findResource(final RDFConnection rdfConnection, final IRI identifier,
            final boolean includeLdpType) {
        return supplyAsync(() -> {
            final TriplestoreResource res = new TriplestoreResource(rdfConnection, identifier, includeLdpType);
            res.fetchData();
            if (!res.exists()) {
                return MISSING_RESOURCE;
            } else if (res.isDeleted()) {
                return DELETED_RESOURCE;
            }
            return res;
        });
    }

    /**
     * Test whether this resource exists.
     * @return true if this resource exists; false otherwise
     */
    protected boolean exists() {
        return getModified() != null && getInteractionModel() != null;
    }

    protected boolean isDeleted() {
        return asIRI(DC.type).filter(isEqual(Trellis.DeletedResource)).isPresent();
    }

    /**
     * Fetch data for this resource.
     *
     * <p>This is equivalent to the following SPARQL query:
     * <pre><code>
     * SELECT ?predicate ?object ?binarySubject ?binaryPredicate ?binaryObject
     * WHERE {
     *   GRAPH trellis:PreferServerManaged {
     *     IDENTIFIER ?predicate ?object
     *     OPTIONAL {
     *       IDENTIFIER dc:hasPart ?binarySubject .
     *       ?binarySubject ?binaryPredicate ?binaryObject
     *     }
     *   }
     * }
     * </code></pre>
     */
    protected void fetchData() {
        LOGGER.debug("Fetching data from RDF datastore for: {}", identifier);
        final Var binarySubject = Var.alloc("binarySubject");
        final Var binaryPredicate = Var.alloc("binaryPredicate");
        final Var binaryObject = Var.alloc("binaryObject");
        final Query q = new Query();
        q.setQuerySelectType();
        q.addResultVar(PREDICATE);
        q.addResultVar(OBJECT);
        q.addResultVar(binarySubject);
        q.addResultVar(binaryPredicate);
        q.addResultVar(binaryObject);

        final ElementPathBlock epb1 = new ElementPathBlock();
        epb1.addTriple(create(rdf.asJenaNode(identifier), PREDICATE, OBJECT));

        final ElementPathBlock epb2 = new ElementPathBlock();
        epb2.addTriple(create(rdf.asJenaNode(identifier), rdf.asJenaNode(DC.hasPart), binarySubject));
        epb2.addTriple(create(rdf.asJenaNode(identifier), rdf.asJenaNode(RDF.type),
                    rdf.asJenaNode(LDP.NonRDFSource)));
        epb2.addTriple(create(binarySubject, binaryPredicate, binaryObject));

        final ElementGroup elg = new ElementGroup();
        elg.addElement(epb1);
        elg.addElement(new ElementOptional(epb2));

        q.setQueryPattern(new ElementNamedGraph(rdf.asJenaNode(Trellis.PreferServerManaged), elg));

        rdfConnection.querySelect(q, qs -> {
            final RDFNode s = qs.get("binarySubject");
            final RDFNode p = qs.get("binaryPredicate");
            final RDFNode o = qs.get("binaryObject");
            nodesToTriple(s, p, o).ifPresent(t -> data.put(t.getPredicate(), t.getObject()));
            data.put(getPredicate(qs), getObject(qs));
        });
    }

    @Override
    public Optional<IRI> getContainer() {
        return asIRI(DC.isPartOf);
    }

    @Override
    public Stream<Quad> stream() {
        return graphMapper.values().stream().flatMap(Supplier::get);
    }

    @Override
    public Stream<Quad> stream(final Collection<IRI> graphNames) {
        return graphNames.stream().filter(graphMapper::containsKey).map(graphMapper::get).flatMap(Supplier::get);
    }

    @Override
    public IRI getIdentifier() {
        return identifier;
    }

    @Override
    public IRI getInteractionModel() {
        return asIRI(RDF.type).orElse(null);
    }

    @Override
    public Optional<IRI> getMembershipResource() {
        return asIRI(LDP.membershipResource);
    }

    @Override
    public Optional<IRI> getMemberRelation() {
        return asIRI(LDP.hasMemberRelation);
    }

    @Override
    public Optional<IRI> getMemberOfRelation() {
        return asIRI(LDP.isMemberOfRelation);
    }

    @Override
    public Optional<IRI> getInsertedContentRelation() {
        return asIRI(LDP.insertedContentRelation);
    }

    @Override
    public Optional<BinaryMetadata> getBinaryMetadata() {
        return asIRI(DC.hasPart).map(id ->
                BinaryMetadata.builder(id).mimeType(asLiteral(DC.format).orElse(null)).build());
    }

    @Override
    public Instant getModified() {
        return asLiteral(DC.modified).map(Instant::parse).orElse(null);
    }

    @Override
    public boolean hasAcl() {
        return fetchAclQuads().findAny().isPresent();
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?subject ?predicate ?object
     * WHERE { GRAPH fromGraphName { ?subject ?predicate ?object } }
     * </code></pre>
     */
    private Stream<Quad> fetchAllFromGraph(final String fromGraphName, final IRI toGraphName) {
        final Query q = new Query();
        q.setQuerySelectType();
        q.addResultVar(SUBJECT);
        q.addResultVar(PREDICATE);
        q.addResultVar(OBJECT);

        final ElementPathBlock epb = new ElementPathBlock();
        epb.addTriple(create(SUBJECT, PREDICATE, OBJECT));

        final ElementGroup elg = new ElementGroup();
        elg.addElement(new ElementNamedGraph(createURI(fromGraphName), epb));

        q.setQueryPattern(elg);

        final Stream.Builder<Quad> builder = builder();
        rdfConnection.querySelect(q, qs -> builder.accept(rdf.createQuad(toGraphName,
                        getSubject(qs), getPredicate(qs), getObject(qs))));
        return builder.build();
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?subject ?predicate ?object
     * WHERE { GRAPH IDENTIFIER?ext=audit { ?subject ?predicate ?object } }
     * </code></pre>
    */
    private Stream<Quad> fetchAuditQuads() {
        return fetchAllFromGraph(identifier.getIRIString() + "?ext=audit", Trellis.PreferAudit);
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?subject ?predicate ?object
     * WHERE { GRAPH IDENTIFIER?ext=acl { ?subject ?predicate ?object } }
     * </code></pre>
    */
    private Stream<Quad> fetchAclQuads() {
        return fetchAllFromGraph(identifier.getIRIString() + "?ext=acl", Trellis.PreferAccessControl);
    }

    private Stream<Quad> fetchMembershipQuads() {
        return concat(fetchIndirectMemberQuads(),
                concat(fetchDirectMemberQuads(), fetchDirectMemberQuadsInverse()));
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?subject ?predicate ?object
     * WHERE {
     *   GRAPH trellis:PreferServerManaged {
     *      ?s ldp:member IDENTIFIER
     *      ?s ldp:membershipResource ?subject
     *      AND ?s rdf:type ldp:IndirectContainer
     *      AND ?s ldp:membershipRelation ?predicate
     *      AND ?s ldp:insertedContentRelation ?o
     *      AND ?res dc:isPartOf ?s .
     *   }
     *   GRAPH ?res { ?res ?o ?object }
     * }
     * </code></pre>
     */
    private Stream<Quad> fetchIndirectMemberQuads() {
        final Var s = Var.alloc("s");
        final Var o = Var.alloc("o");
        final Var res = Var.alloc("res");

        final Query q = new Query();
        q.setQuerySelectType();
        q.addResultVar(SUBJECT);
        q.addResultVar(PREDICATE);
        q.addResultVar(OBJECT);

        final ElementPathBlock epb1 = new ElementPathBlock();
        epb1.addTriple(create(s, rdf.asJenaNode(LDP.member), rdf.asJenaNode(identifier)));
        epb1.addTriple(create(s, rdf.asJenaNode(LDP.membershipResource), SUBJECT));
        epb1.addTriple(create(s, rdf.asJenaNode(RDF.type), rdf.asJenaNode(LDP.IndirectContainer)));
        epb1.addTriple(create(s, rdf.asJenaNode(LDP.hasMemberRelation), PREDICATE));
        epb1.addTriple(create(s, rdf.asJenaNode(LDP.insertedContentRelation), o));
        epb1.addTriple(create(res, rdf.asJenaNode(DC.isPartOf), s));

        final ElementPathBlock epb2 = new ElementPathBlock();
        epb2.addTriple(create(res, o, OBJECT));

        final ElementGroup elg = new ElementGroup();
        elg.addElement(new ElementNamedGraph(rdf.asJenaNode(Trellis.PreferServerManaged), epb1));
        elg.addElement(new ElementNamedGraph(res, epb2));

        q.setQueryPattern(elg);

        final Stream.Builder<Quad> builder = builder();
        rdfConnection.querySelect(q, qs ->
            builder.accept(rdf.createQuad(LDP.PreferMembership, getSubject(qs), getPredicate(qs), getObject(qs))));
        return builder.build();
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?subject ?predicate ?object
     * WHERE {
     *   GRAPH trellis:PreferServerManaged {
     *      ?s ldp:member IDENTIFIER
     *      ?s ldp:membershipResource ?subject
     *      AND ?s ldp:hasMemberRelation ?predicate
     *      AND ?s ldp:insertedContentRelation ldp:MemberSubject
     *      AND ?object dc:isPartOf ?s }
     * }
     * </code></pre>
     */
    private Stream<Quad> fetchDirectMemberQuads() {
        final Query q = new Query();
        q.setQuerySelectType();
        q.addResultVar(SUBJECT);
        q.addResultVar(PREDICATE);
        q.addResultVar(OBJECT);
        final Var s = Var.alloc("s");

        final ElementPathBlock epb = new ElementPathBlock();
        epb.addTriple(create(s, rdf.asJenaNode(LDP.member), rdf.asJenaNode(identifier)));
        epb.addTriple(create(s, rdf.asJenaNode(LDP.membershipResource), SUBJECT));
        epb.addTriple(create(s, rdf.asJenaNode(LDP.hasMemberRelation), PREDICATE));
        epb.addTriple(create(s, rdf.asJenaNode(LDP.insertedContentRelation), rdf.asJenaNode(LDP.MemberSubject)));
        epb.addTriple(create(OBJECT, rdf.asJenaNode(DC.isPartOf), s));

        final ElementNamedGraph ng = new ElementNamedGraph(rdf.asJenaNode(Trellis.PreferServerManaged), epb);

        final ElementGroup elg = new ElementGroup();
        elg.addElement(ng);

        q.setQueryPattern(elg);

        final Stream.Builder<Quad> builder = builder();
        rdfConnection.querySelect(q, qs -> builder.accept(rdf.createQuad(LDP.PreferMembership, getSubject(qs),
                        getPredicate(qs), getObject(qs))));
        return builder.build();
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?predicate ?object
     * WHERE {
     *   GRAPH trellis:PreferServerManaged {
     *      IDENTIFIER dc:isPartOf ?subject .
     *      ?subject ldp:isMemberOfRelation ?predicate .
     *      ?subject ldp:membershipResource ?object .
     *      ?subject ldp:insertedContentRelation ldp:MemberSubject .
     *   }
     * }
     * </code></pre>
     */
    private Stream<Quad> fetchDirectMemberQuadsInverse() {
        final Query q = new Query();
        q.setQuerySelectType();
        q.addResultVar(PREDICATE);
        q.addResultVar(OBJECT);

        final ElementPathBlock epb = new ElementPathBlock();
        epb.addTriple(create(rdf.asJenaNode(identifier), rdf.asJenaNode(DC.isPartOf), SUBJECT));
        epb.addTriple(create(SUBJECT, rdf.asJenaNode(LDP.isMemberOfRelation), PREDICATE));
        epb.addTriple(create(SUBJECT, rdf.asJenaNode(LDP.membershipResource), OBJECT));
        epb.addTriple(create(SUBJECT, rdf.asJenaNode(LDP.insertedContentRelation), rdf.asJenaNode(LDP.MemberSubject)));

        final ElementNamedGraph ng = new ElementNamedGraph(rdf.asJenaNode(Trellis.PreferServerManaged), epb);

        final ElementGroup elg = new ElementGroup();
        elg.addElement(ng);

        q.setQueryPattern(elg);

        final Stream.Builder<Quad> builder = builder();
        rdfConnection.querySelect(q, qs -> builder.accept(rdf.createQuad(LDP.PreferMembership, identifier,
                        getPredicate(qs), getObject(qs))));
        return builder.build();
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?object
     * WHERE {
     *   GRAPH trellis:PreferServerManaged { ?object dc:isPartOf IDENTIFIER }
     * }
     * </code></pre>
     */
    private Stream<Quad> fetchContainmentQuads() {
        if (getInteractionModel().getIRIString().endsWith("Container")) {
            final Query q = new Query();
            q.setQuerySelectType();
            q.addResultVar(OBJECT);

            final ElementPathBlock epb = new ElementPathBlock();
            epb.addTriple(create(OBJECT, rdf.asJenaNode(DC.isPartOf), rdf.asJenaNode(identifier)));

            final ElementNamedGraph ng = new ElementNamedGraph(rdf.asJenaNode(Trellis.PreferServerManaged), epb);

            final ElementGroup elg = new ElementGroup();
            elg.addElement(ng);
            q.setQueryPattern(elg);

            final Stream.Builder<Quad> builder = builder();
            rdfConnection.querySelect(q, qs -> builder.accept(rdf.createQuad(LDP.PreferContainment,
                            identifier, LDP.contains, getObject(qs))));
            return builder.build();
        }
        return Stream.empty();
    }

    /**
     * This code is equivalent to the SPARQL query below.
     *
     * <p><pre><code>
     * SELECT ?subject ?predicate ?object
     * WHERE { GRAPH IDENTIFIER { ?subject ?predicate ?object } }
     * </code></pre>
     */
    private Stream<Quad> fetchUserQuads() {
        if (includeLdpType) {
            return concat(of(rdf.createQuad(Trellis.PreferUserManaged, identifier, RDF.type, getInteractionModel())),
                    fetchAllFromGraph(identifier.getIRIString(), Trellis.PreferUserManaged));
        }
        return fetchAllFromGraph(identifier.getIRIString(), Trellis.PreferUserManaged);
    }

    private Optional<IRI> asIRI(final IRI predicate) {
        return ofNullable(data.get(predicate)).filter(IRI.class::isInstance).map(IRI.class::cast);
    }

    private Optional<String> asLiteral(final IRI predicate) {
        return ofNullable(data.get(predicate)).filter(Literal.class::isInstance).map(Literal.class::cast)
            .map(Literal::getLexicalForm);
    }
}
