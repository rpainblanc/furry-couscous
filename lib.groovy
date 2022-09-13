import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

import java.util.regex.Matcher
import java.util.regex.Pattern

def static getBuilderTemplateGithubRegex() {
    return '\\A@jenkins-dataiku\\s*\\r\\n(```|~~~)(json)?\\r\\n(?<BUILDERCONF>\\{.+\\})\\r\\n\\1.*\\Z'
}

@NonCPS
def getGitHubPRIssueComments(String github_token, String pr_number) {
    int page_size = 100
    String base_url = "https://api.github.com/repos/dataiku/dip/issues/${pr_number}/comments?per_page=${page_size}"
    int page = 1
    def all_comments = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def comments = new JsonSlurperClassic().parseText(request.content.text as String)
        int count = 0
        for (comment in comments) {
            all_comments.add(comment)
            count++
        }

        if (count == page_size) {
            page += 1
        } else {
            page = 0
        }
    }

    return all_comments
}

@NonCPS
def getGitHubPRIssueTimelineEvents(String github_token, String pr_number) {
    // Return the issues in the same order so that we can paginate and get all issues
    int page_size = 100
    String base_url = "https://api.github.com/repos/dataiku/dip/issues/${pr_number}/timeline?per_page=${page_size}"
    int page = 1
    def all_events = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def events = new JsonSlurperClassic().parseText(request.content.text as String)
        int count = 0
        for (event in events) {
            all_events.add(event)
            count++
        }

        if (count == page_size) {
            page += 1
        } else {
            page = 0
        }
    }

    return all_events
}

def findBuilderTemplateInGithubIssueComments(def github_issue_comments) {
    Pattern pattern
    Matcher matcher
    def bt_comments = []
    try {
        pattern = Pattern.compile(getBuilderTemplateGithubRegex(), Pattern.DOTALL)
        for (comment in github_issue_comments) {
            matcher = pattern.matcher(comment.body as String)
            if (matcher.matches()) {
                println("Found an issue comment matching the pattern with a builder template: html_url=${comment.html_url}, created_at=${comment.created_at}, updated_at=${comment.updated_at}")
                bt_comments.add(comment)
            }
        }

        sort_issue_comments_by_creation(bt_comments)
        def last_created = !bt_comments.isEmpty() ? bt_comments.last() : null
        sort_issue_comments_by_update(bt_comments)
        def last_updated = !bt_comments.isEmpty() ? bt_comments.last() : null

        if (last_created != null && last_updated != null) {
            println("The last created issue comment is: html_url=${last_created.html_url}, created_at=${last_created.created_at}, updated_at=${last_created.updated_at}")
            println("The last updated issue comment is: html_url=${last_updated.html_url}, created_at=${last_updated.created_at}, updated_at=${last_updated.updated_at}")
            def comment = last_created
            if (last_created.id == last_updated.id) {
                // OK: last updated and last created are the same comment
                println("INFO Last created and last updated are the same issue comment")
            } else {
                // WARNING: an older comment has been updated after the last one was created but it won't be used
                println("WARNING Last created and last updated are different issue comments, will use the last created one instead of the last updated one")
            }
            matcher = pattern.matcher(comment.body as String)
            matcher.matches() // Match was already tested earlier
            return matcher.group("BUILDERCONF")
        } else {
            return null
        }
    } finally {
        // BEWARE java.util.regex.[Pattern | Matcher] are not serializable, make sure all of them are set to null before exiting the method
        pattern = null
        matcher = null
    }
}

@NonCPS
def sort_issue_comments_by_creation(def github_issue_comments) {
    github_issue_comments.sort { c1, c2 -> c1.created_at <=> c2.created_at }
}

@NonCPS
def sort_issue_comments_by_update(def github_issue_comments) {
    github_issue_comments.sort { c1, c2 -> c1.updated_at <=> c2.updated_at }
}

return this
