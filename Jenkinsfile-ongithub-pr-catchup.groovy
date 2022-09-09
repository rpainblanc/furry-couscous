import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

import java.time.OffsetDateTime

def repository_owner = 'rpainblanc'
def repository_name = 'furry-couscous'

@NonCPS
def getGitHubPRIssueEvents(String repository_owner, String repository_name, String github_token, def pr_number) {
    // Return the issues in the same order so that we can paginate and get all issues
    int page_size = 100
    String base_url = "https://api.github.com/repos/${repository_owner}/${repository_name}/issues/${pr_number}/events?sort=created&direction=asc&state=open&per_page=${page_size}"
    int page = 1
    def all_events = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def events = new JsonSlurperClassic().parseText(request.content.text)
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

@NonCPS
def getGitHubPRIssueTimelineEvents(String repository_owner, String repository_name, String github_token, def pr_number) {
    // Return the issues in the same order so that we can paginate and get all issues
    int page_size = 100
    String base_url = "https://api.github.com/repos/${repository_owner}/${repository_name}/issues/${pr_number}/timeline?per_page=${page_size}"
    int page = 1
    def all_events = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def events = new JsonSlurperClassic().parseText(request.content.text)
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

@NonCPS
def getGitHubPRWithLabels(String repository_owner, String repository_name, String github_token, def labels) {
    String labels_filter = ''
    if (labels != null) {
        labels_filter = labels.join(',')
    }
    // Return the issues in the same order so that we can paginate and get all issues
    int page_size = 100
    String base_url = "https://api.github.com/repos/${repository_owner}/${repository_name}/issues?sort=created&direction=asc&state=open&per_page=${page_size}&labels=${labels_filter}"
    int page = 1
    def all_issues = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def issues = new JsonSlurperClassic().parseText(request.content.text)
        int count = 0
        for (issue in issues) {
            all_issues.add(issue)
            count++
        }

        if (count == page_size) {
            page += 1
        } else {
            page = 0
        }
    }

    return all_issues
}

node('built-in') {
    sh 'printenv | sort'
    deleteDir()
    def messages = []
    withCredentials([
            usernamePassword(credentialsId: 'github-access-for-jenkins-tests-token', usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_PASSWORD')]) {
        try {
            def github_prs = getGitHubPRWithLabels(repository_owner, repository_name, env.GITHUB_PASSWORD, ['integration-tests', 'build-ondemand'])
            //def github_prs = getGitHubPRWithLabels(repository_owner, repository_name, env.GITHUB_PASSWORD, ['integration-tests'])
            //def github_prs = readJSON file: 'data.json'
            writeJSON file: 'data.json', json: github_prs
            for (pr in github_prs) {
                def job_name = "dip-on-github-pr/PR-${pr.number}"
                def job = Jenkins.get().getItemByFullName(job_name)
                println "====\r\nPR ${pr.number} (${pr.pull_request.url})\r\n===="

                if (!job) {
                    // There is no Jenkins job yet for this PR
                    messages.add("There is no job yet for PR ${pr.number} (${pr.pull_request.url}), waiting for the GitHub plugin to create one soon")
                    continue
                }
                if (job.buildable && !job.inQueue && !job.building) {
                    def last_build = job.lastCompletedBuild
                    if (!last_build)  {
                        // There is no build yet but the plugin should trigger one soon
                        messages.add("Job exists for PR ${pr.number} (${pr.pull_request.url}) but no build exists yet, waiting for the GitHub plugin to trigger one soon")
                        // Set all non-serializable objects to null before entering Jenkins step
                        job = null
                        last_build = null
                        continue
                    }
                    //job.scheduleBuild(0, new hudson.model.Cause.UserIdCause("jenkins"))
                    def tm_events = getGitHubPRIssueTimelineEvents(repository_owner, repository_name, env.GITHUB_PASSWORD, pr.number)
                    // Scan the timeline events received **after** the last build start time
                    def tm_events_to_check = []
                    for (event in tm_events) {
                        def date
                        String event_details
                        if (event.event == 'committed') {
                            date = event.committer.date
                            event_details = "message=${event.message}"
                        } else if (event.event == 'labeled') {
                            date = event.created_at
                            event_details = "label=${event.label.name}"
                        } else if (event.event == 'commented') {
                            date = event.created_at
                            event_details = "body=${event.body}"
                        } else {
                            // Not interesting event
                            continue
                        }
                        def date_millis = OffsetDateTime.parse(date).toEpochSecond() * 1000
                        if (date_millis < last_build.startTimeInMillis) {
                            // Event before last execution time    
                            println "Event ${event.event} (URL=${event.url}, ${event_details}): date ${date_millis} is before last build time ${last_build.startTimeInMillis}"
                            continue
                        }
                        println "Event ${event.event} (URL=${event.url}, ${event_details}): date ${date_millis} is after last build time ${last_build.startTimeInMillis}"
                        tm_events_to_check.add(event)
                    }

                    def skip_pr = false
                    for (event in tm_events_to_check) {
                        if (event.event == 'committed') {
                            // There is a commit more recent than the last execution time
                            messages.add("A commit exists on PR ${pr.number} (${pr.pull_request.url}) which is more recent than the last build ${last_build.id}, waiting for the GitHub plugin to trigger a new one soon")
                            skip_pr = true
                            break
                        }
                        
                        if (event.event == 'labeled') {
                            // Since we are already filtering PRs having the expected labels, we don't care which
                            // label was added (this label may even have been removed since), just that any label was added
                            messages.add("A label was added on PR ${pr.number} (${pr.pull_request.url}) after the last build ${last_build.id} was executed, need to trigger a new build explicitly")
                            break
                        }
                        
                        if (event.event == 'commented') {
                            // Check comment content is actually a builder template
                            // TODO
                        }
                    }
                    job = null
                    last_build = null
                    writeJSON file: "PR-${pr.number}-tm-events.json", json: tm_events
                    if (skip_pr) {
                        println "Skip PR ${pr.number}"
                    } else {
                        println "Should trigger PR ${pr.number}"
                    }
                }
            }
        } finally {
            writeJSON file: "messages.json", json: messages
            archiveArtifacts artifacts: '*.json', allowEmptyArchive: true
        }
    }
}
