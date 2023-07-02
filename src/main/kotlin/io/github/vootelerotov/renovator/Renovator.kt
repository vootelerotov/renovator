package io.github.vootelerotov.renovator

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.spotify.github.v3.clients.GitHubClient
import com.spotify.github.v3.clients.PullRequestClient
import com.spotify.github.v3.prs.ImmutableMergeParameters
import com.spotify.github.v3.prs.ImmutableReviewParameters
import com.spotify.github.v3.prs.MergeMethod
import com.spotify.github.v3.prs.PullRequest
import com.spotify.github.v3.search.SearchIssue
import com.spotify.github.v3.search.requests.ImmutableSearchParameters
import com.spotify.githubclient.shade.okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull

private const val RENOVATE_APP = "app/renovate"
private const val APPROVE_EVENT = "APPROVE"

fun main(args: Array<String>) = Renovator().main(args)

class Renovator : CliktCommand() {

  private val token by mutuallyExclusiveOptions(
      option("--token", help = "GitHub token to use"),
      option("--token-variable", help = "Name of an environment variable to read GitHub token from").convert { System.getenv(it) }
  ).required()

  private val organization by option("-o", "--org", help = "GitHub organization to renovate").required()

  private val user by option("-u", "--user", help = "GitHub user who we are renovating for").required()

  private val author by option("-a", "--author", help = "The creator of renovate request").default(RENOVATE_APP)

  private val dependencyFilterOptions by object : OptionGroup() {
    val dependency by option("-d", "--dependency", help = "The dependency to renovate").required()
    val yes by option("-y", "--yes", help = "Approve all matching PR-s").flag()
  }.cooccurring()

  private val debug by option("--debug", help = "Enables additional output").flag()

  private val defaultComment by option("-m", "-c", "--comment", help = "The default comment for PR approvals")

  override fun run() {
    withHttpClient(this::renovate)
  }

  private fun renovate(httpClient: OkHttpClient) {
    val githubClient = GitHubClient.create(httpClient, URI.create("https://api.github.com/"), token)

    val renovatePrs = githubClient.createSearchClient().issues(
      ImmutableSearchParameters.builder()
        .q("org:$organization author:$author is:open is:pr review-requested:$user")
        .build()
    ).get(5, TimeUnit.SECONDS).items() ?: throw IllegalStateException("Could not get pr-s for user $user")

    echo("Found ${renovatePrs.size} renovate PR-s for user $user")

    val matchingPrs = dependencyFilterOptions?.dependency?.let { dependency ->
      renovatePrs.filter {
        it.title()?.contains(dependency) ?: false
      }.also { echo("Found ${it.size} renovate PR-s for dependency $dependency") }
    } ?: renovatePrs

    matchingPrs.sortedBy { it.title() }.forEach { prIssue ->
      val pullRequestClient = pullRequestClient(prIssue, githubClient)
      pullRequestClient.get(prIssue.number()!!).get(5, TimeUnit.SECONDS).let {
        merge(it, pullRequestClient, dependencyFilterOptions?.yes ?: false)
      }
    }

  }

  private fun merge(pr: PullRequest, pullRequestClient: PullRequestClient, confirmedViaFlag: Boolean = false) {
    if (pr.merged() != false) {
      echo("PR ${prDescription(pr)} is already merged")
      return
    }

    if (pr.mergeable().getOrNull() != true) {
      echo("PR ${prDescription(pr)} cannot be merged")
      return
    }

    val isConfirmed = confirmedViaFlag || confirm("Approve and merge PR ${prDescription(pr)}?")
      ?: throw IllegalStateException("Can not get confirmation from stdin")

    if (!isConfirmed) {
      return
    }

    val comment = prompt("Enter comment to approve the PR with: ", default = defaultComment, promptSuffix = "")
      ?: throw IllegalStateException("Can not get comment from stdin")

    debug("Approving PR ${prDescription(pr)} with comment $comment")

    pullRequestClient.createReview(pr.number()!!, ImmutableReviewParameters.builder()
      .body(comment)
      .event(APPROVE_EVENT)
      .build()
    ).get()
    debug("Merging PR ${prDescription(pr)} via re-base")
    pullRequestClient.merge(
      pr.number()!!,
      ImmutableMergeParameters.builder().mergeMethod(MergeMethod.rebase).sha(pr.head()?.sha()!!).build()
    ).get()
    echo("")
  }

  private fun pullRequestClient(pr: SearchIssue, githubClient: GitHubClient): PullRequestClient {
    val (org, name) = pr.repositoryUrl()?.getOrNull()?.path?.let { path ->
      path.substringAfter("/repos/").split("/")
    } ?: throw IllegalStateException("Could not get repository full name from ${pr.repositoryUrl()?.getOrNull()}")
    return githubClient.createRepositoryClient(org, name).createPullRequestClient()
  }

  private fun prDescription(it: PullRequest) = "[title: ${it.title()}, url: ${it.htmlUrl()} ]"

  private fun withHttpClient(task: (OkHttpClient) -> Unit) {
    val httpClient = OkHttpClient()
    try {
      task(httpClient)
    } finally {
      httpClient.dispatcher().executorService().shutdown()
      httpClient.connectionPool().evictAll()
      httpClient.cache()?.close()
    }
  }

  private fun debug(message: String) = takeIf { debug }?.let { echo(message) }

}