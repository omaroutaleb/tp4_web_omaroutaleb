package com.project.tp4_omaroutaleb_web.llm;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.enterprise.context.Dependent;

import java.io.Serializable;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service LLM avec support de 4 rôles :
 * 1. Assistant RAG Naïf (Test1 + Test4)
 * 2. Assistant RAG avec Routage (Test3)
 * 3. Traducteur
 * 4. Guide touristique
 */
@Dependent
public class LlmClient implements Serializable {

    private static final Logger LOGGER = Logger.getLogger(LlmClient.class.getName());

    private String systemRole;
    private Assistant assistant;
    private ChatLanguageModel model;
    private ChatMemory chatMemory;

    /**
     * Définit le rôle système et initialise l'assistant
     */
    public void setSystemRole(String systemRole) {
        this.systemRole = systemRole;

        try {
            configureLogger();

            String llmKey = System.getenv("GEMINI_API_KEY");
            if (llmKey == null || llmKey.isBlank()) {
                throw new RuntimeException("GEMINI_API_KEY non définie");
            }

            // Créer le ChatModel avec logging
            model = GoogleAiGeminiChatModel.builder()
                    .apiKey(llmKey)
                    .modelName("gemini-2.0-flash-exp")
                    .temperature(0.3)
                    .logRequestsAndResponses(true)
                    .build();

            chatMemory = MessageWindowChatMemory.withMaxMessages(10);

            // Déterminer le mode selon le rôle
            if (systemRole.contains("route") || systemRole.contains("Routage")) {
                LOGGER.info("🤖 Mode : Assistant RAG avec Routage (Test3)");
                initRoutingMode();
            } else if (systemRole.contains("RAG") || systemRole.contains("Retrieval-Augmented")) {
                LOGGER.info("🤖 Mode : Assistant RAG Naïf (Test1 + Test4)");
                initNaiveRagMode();
            } else {
                LOGGER.info("🤖 Mode : Assistant standard (sans RAG)");
                initStandardMode();
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erreur lors de l'initialisation", e);
            throw new RuntimeException("Erreur d'initialisation du LLM", e);
        }
    }

    /**
     * Mode RAG Naïf : Combine Test1 + Test4
     */
    private void initNaiveRagMode() throws Exception {
        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        EmbeddingStore<TextSegment> embeddingStore = ingestDocuments(embeddingModel);

        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.5)
                .build();

        // QueryRouter intelligent (Test4)
        QueryRouter smartRouter = new QueryRouter() {
            @Override
            public List<ContentRetriever> route(Query query) {
                PromptTemplate template = PromptTemplate.from(
                        "Est-ce que la question suivante porte sur l'intelligence artificielle, " +
                                "le RAG, le fine-tuning, LangChain4j, ou les embeddings ? " +
                                "Question : '{{question}}'\n" +
                                "Réponds uniquement par 'oui' ou 'non'."
                );

                Prompt prompt = template.apply(Map.of("question", query.text()));
                String decision = model.generate(prompt.text());

                if (decision.toLowerCase().contains("oui")) {
                    LOGGER.info("✅ RAG activé pour : " + query.text());
                    return Collections.singletonList(contentRetriever);
                } else {
                    LOGGER.info("⚠️ RAG désactivé pour : " + query.text());
                    return Collections.emptyList();
                }
            }
        };

        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(smartRouter)
                .build();

        chatMemory.add(SystemMessage.from(systemRole));

        assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .chatMemory(chatMemory)
                .retrievalAugmentor(retrievalAugmentor)
                .build();

        LOGGER.info("✅ RAG Naïf initialisé (Test1 + Test4)");
    }

    /**
     * Mode Routage : Test3 - Route vers le document approprié
     */
    private void initRoutingMode() throws Exception {
        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // Créer 2 embedding stores séparés
        EmbeddingStore<TextSegment> ragStore = ingestSingleDocumentToStore("/rag.pdf", embeddingModel);
        EmbeddingStore<TextSegment> langchainStore = ingestSingleDocumentToStore("/autre-document.pdf", embeddingModel);

        // Créer 2 ContentRetrievers distincts
        ContentRetriever ragRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(ragStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.5)
                .build();

        ContentRetriever langchainRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(langchainStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.5)
                .build();

        // Créer les descriptions pour le routeur
        Map<ContentRetriever, String> descriptions = new HashMap<>();
        descriptions.put(ragRetriever,
                "Support de cours sur le RAG (Retrieval Augmented Generation) et le fine-tuning en intelligence artificielle");
        descriptions.put(langchainRetriever,
                "Support de cours sur LangChain4j : présentation, modèles, AiServices, extraction de données, outils, modération et streaming");

        // Créer le LanguageModelQueryRouter (Test3)
        QueryRouter languageModelRouter = new LanguageModelQueryRouter(model, descriptions);

        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(languageModelRouter)
                .build();

        chatMemory.add(SystemMessage.from(systemRole));

        assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .chatMemory(chatMemory)
                .retrievalAugmentor(retrievalAugmentor)
                .build();

        LOGGER.info("✅ RAG avec Routage initialisé (Test3)");
    }

    /**
     * Mode standard : Sans RAG
     */
    private void initStandardMode() {
        chatMemory.add(SystemMessage.from(systemRole));

        assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .chatMemory(chatMemory)
                .build();

        LOGGER.info("✅ Assistant standard initialisé (sans RAG)");
    }

    /**
     * Ingère les 2 documents dans un seul embedding store
     */
    private EmbeddingStore<TextSegment> ingestDocuments(EmbeddingModel embeddingModel) throws Exception {
        EmbeddingStore<TextSegment> combinedStore = new InMemoryEmbeddingStore<>();

        URL file1 = getClass().getResource("/rag.pdf");
        if (file1 == null) throw new RuntimeException("rag.pdf introuvable!");
        Path path1 = Paths.get(file1.toURI());
        ingestSingleDocument(path1, embeddingModel, combinedStore);
        LOGGER.info("✅ rag.pdf ingéré");

        URL file2 = getClass().getResource("/autre-document.pdf");
        if (file2 == null) throw new RuntimeException("autre-document.pdf introuvable!");
        Path path2 = Paths.get(file2.toURI());
        ingestSingleDocument(path2, embeddingModel, combinedStore);
        LOGGER.info("✅ autre-document.pdf ingéré");

        return combinedStore;
    }

    /**
     * Ingère un document dans son propre embedding store (pour Test3)
     */
    private EmbeddingStore<TextSegment> ingestSingleDocumentToStore(String resourcePath, EmbeddingModel embeddingModel) throws Exception {
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        URL file = getClass().getResource(resourcePath);
        if (file == null) throw new RuntimeException(resourcePath + " introuvable!");
        Path path = Paths.get(file.toURI());

        ingestSingleDocument(path, embeddingModel, store);
        LOGGER.info("✅ " + resourcePath + " ingéré dans son propre store");

        return store;
    }

    /**
     * Ingère un document dans un embedding store donné
     */
    private void ingestSingleDocument(Path path, EmbeddingModel embeddingModel,
                                      EmbeddingStore<TextSegment> store) throws Exception {
        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(path, parser);

        DocumentSplitter splitter = DocumentSplitters.recursive(600, 0);
        List<TextSegment> segments = splitter.split(document);

        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        List<Embedding> embeddings = response.content();

        store.addAll(embeddings, segments);

        LOGGER.info("   → " + segments.size() + " segments créés");
    }

    /**
     * Configure le logging
     */
    private void configureLogger() {
        Logger packageLogger = Logger.getLogger("dev.langchain4j");
        packageLogger.setLevel(Level.FINE);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        packageLogger.addHandler(handler);
    }

    /**
     * Envoie une question au LM
     */
    public String envoyerQuestion(String userMessage) {
        if (assistant == null) {
            return "❌ Erreur : Assistant non initialisé.";
        }

        try {
            LOGGER.info("💬 Question : " + userMessage);
            String response = assistant.chat(userMessage);
            LOGGER.info("🤖 Réponse générée");
            return response;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erreur", e);
            return "❌ Erreur : " + e.getMessage();
        }
    }
}
