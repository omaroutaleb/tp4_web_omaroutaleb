package com.project.tp4_omaroutaleb_web.jsf;

import com.project.tp4_omaroutaleb_web.llm.LlmClient;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.model.SelectItem;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Backing bean pour la page JSF index.xhtml.
 * Portée view pour conserver l'état de la conversation.
 *
 * Gère 4 rôles :
 * 1. Assistant RAG Naïf (Test1 + Test4)
 * 2. Assistant RAG avec Routage (Test3)
 * 3. Traducteur Anglais-Français (TP2)
 * 4. Guide touristique (TP2)
 */
@Named
@ViewScoped
public class Bb implements Serializable {

    private String roleSysteme;
    private boolean roleSystemeChangeable = true;
    private List<SelectItem> listeRolesSysteme;
    private String question;
    private String reponse;
    private StringBuilder conversation = new StringBuilder();

    @Inject
    private FacesContext facesContext;

    @Inject
    private LlmClient llmClient;

    public Bb() {
        // Constructeur par défaut
    }

    // ========== GETTERS & SETTERS ==========

    public String getRoleSysteme() {
        return roleSysteme;
    }

    public void setRoleSysteme(String roleSysteme) {
        this.roleSysteme = roleSysteme;
    }

    public boolean isRoleSystemeChangeable() {
        return roleSystemeChangeable;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getReponse() {
        return reponse;
    }

    public void setReponse(String reponse) {
        this.reponse = reponse;
    }

    public String getConversation() {
        return conversation.toString();
    }

    public void setConversation(String conversation) {
        this.conversation = new StringBuilder(conversation);
    }

    // ========== MÉTHODES D'ACTION ==========

    /**
     * Envoie la question au service LLM/RAG
     */
    public String envoyer() {
        if (question == null || question.isBlank()) {
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Texte question vide", "Il manque le texte de la question");
            facesContext.addMessage(null, message);
            return null;
        }

        // Si c'est la première question, définir le rôle système et initialiser
        if (this.conversation.isEmpty()) {
            try {
                llmClient.setSystemRole(roleSysteme);
                this.roleSystemeChangeable = false;
            } catch (Exception e) {
                this.reponse = "❌ Erreur lors de l'initialisation du LLM : " + e.getMessage();
                FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                        "Erreur d'initialisation", e.getMessage());
                facesContext.addMessage(null, message);
                return null;
            }
        }

        // Appel au LLM via le service
        try {
            this.reponse = llmClient.envoyerQuestion(question);
        } catch (Exception e) {
            this.reponse = "❌ Erreur lors de la communication avec le LLM : " + e.getMessage();
            FacesMessage message = new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Erreur LLM", e.getMessage());
            facesContext.addMessage(null, message);
        }

        // Afficher la conversation
        afficherConversation();
        return null;
    }

    /**
     * Pour démarrer un nouveau chat
     */
    public String nouveauChat() {
        // Réinitialiser toutes les variables
        this.conversation = new StringBuilder();
        this.question = "";
        this.reponse = "";
        this.roleSystemeChangeable = true;
        this.roleSysteme = null;

        // Recharger la page
        return "index?faces-redirect=true";
    }

    /**
     * Affiche la conversation dans le textArea
     */
    private void afficherConversation() {
        this.conversation.append("== 👤 User:\n")
                .append(question)
                .append("\n\n== 🤖 Assistant:\n")
                .append(reponse)
                .append("\n")
                .append("═".repeat(80))
                .append("\n\n");
    }

    // ========== LISTE DES RÔLES ==========

    /**
     * Liste des rôles système prédéfinis (4 rôles au total)
     */
    public List<SelectItem> getRolesSysteme() {
        if (this.listeRolesSysteme == null) {
            this.listeRolesSysteme = new ArrayList<>();

            // ==================== RÔLES TP4 (AVEC RAG) ====================

            // RÔLE 1 : Assistant RAG Naïf (Test1 + Test4)
            String role = """
                You are a helpful AI assistant specialized in Retrieval-Augmented Generation (RAG),
                fine-tuning, LangChain4j, and embeddings. You help users understand these concepts
                and answer their questions based on the provided documents.
                When a question is not related to AI or RAG topics, you can still answer it but
                without using the document retrieval system.
                """;
            this.listeRolesSysteme.add(new SelectItem(role, "Assistant RAG Naïf"));

            // RÔLE 2 : Assistant RAG avec Routage (Test3)
            role = """
                You are an intelligent AI assistant that can route questions to the appropriate document.
                When asked about RAG, fine-tuning, or embeddings, you use the RAG document.
                When asked about LangChain4j, AiServices, or tools, you use the LangChain4j document.
                You automatically determine which document is most relevant and provide detailed answers
                based on the chosen source.
                """;
            this.listeRolesSysteme.add(new SelectItem(role, "Assistant RAG avec Routage"));

            // ==================== RÔLES TP2 (SANS RAG) ====================

            // RÔLE 3 : Traducteur
            role = """
                You are an interpreter. You translate from English to French and from French to English.
                If the user types French text, you translate it into English.
                If the user types English text, you translate it into French.
                Keep the translations accurate and natural.
                """;
            this.listeRolesSysteme.add(new SelectItem(role, "Traducteur Anglais-Français"));

            // RÔLE 4 : Guide touristique
            role = """
                You are a travel guide. If the user types the name of a country or a town,
                you tell them what are the main places to visit and the average price of a meal.
                Provide practical travel information and recommendations.
                """;
            this.listeRolesSysteme.add(new SelectItem(role, "Guide touristique"));
        }

        return this.listeRolesSysteme;
    }
}
