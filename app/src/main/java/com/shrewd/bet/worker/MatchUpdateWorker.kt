package com.shrewd.bet.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shrewd.bet.FootballWidgetProvider
import com.shrewd.bet.network.RetrofitClient
import com.shrewd.bet.repository.MatchRepository
import com.shrewd.bet.util.LogoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MatchUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = MatchRepository(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val result = repository.fetchMatches()

            result.fold(
                onSuccess = {
                    // UI update is handled by calling the provider
                    FootballWidgetProvider.updateAllWidgets(applicationContext)
                    Result.success()
                },
                onFailure = { 
                    Result.retry() 
                }
            )
        } catch (e: Exception) {
            Log.e("MatchUpdateWorker", "Work failed", e)
            Result.retry()
        }
    }
}
