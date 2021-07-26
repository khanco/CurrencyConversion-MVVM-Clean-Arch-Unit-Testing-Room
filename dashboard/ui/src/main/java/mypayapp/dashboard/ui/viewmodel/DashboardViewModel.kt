package mypayapp.dashboard.ui.viewmodel

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mypayapp.dashboard.data.TIME_STAMP_WHEN_RATES_WAS_FETCHED
import mypayapp.dashboard.domain.DashboardUseCase
import mypayapp.data.utils.EXCHANGE_RATE_REFRESH_INTERVAL
import mypayapp.data.utils.NO_INTERNET_ERROR
import mypayapp.data.utils.SOMETHING_WENT_WRONG
import mypayapp.domain.models.BaseResponse
import mypayapp.domain.models.QuoteEntity
import mypayapp.ui.base.BaseViewModel
import org.json.JSONException
import org.json.JSONObject

class DashboardViewModel(
    private val useCase: DashboardUseCase,
) : BaseViewModel() {

    val amount = MediatorLiveData<String>().apply {
        value = "1"
    }
    val listOfQuotes = MutableLiveData<ArrayList<QuoteEntity>>()

    fun getDataForExchangeRates() {
        showProgressBar.set(true)
        if (checkIfTimeStampExpired(useCase.getLastSavedTimeStamp())) {
            hitApi()
        } else {
            getDataFromLocal()
        }
    }

    private fun checkIfTimeStampExpired(lastSavedTimeStamp: Long) =
        lastSavedTimeStamp == 0L || System.currentTimeMillis() - lastSavedTimeStamp >= EXCHANGE_RATE_REFRESH_INTERVAL

    private fun getDataFromLocal() {
        var listData: List<QuoteEntity>
        viewModelScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                listData = useCase.getSavedExchangeRates()
            }
            listOfQuotes.value = ArrayList(listData)
//            showProgressBar.value = false
        }
    }

    private fun hitApi() {
        viewModelScope.launch(Dispatchers.Main) {
            when (val response = withContext(Dispatchers.IO) {
                useCase.fetchExchangeRates()
            }) {
                is BaseResponse.NetworkError -> {
                    errorMessage.value = NO_INTERNET_ERROR
                }
                is BaseResponse.Error -> {
                    errorMessage.value = response.exception.message
                }
                is BaseResponse.ApiError -> {
                    errorMessage.value = response.message
                }
                is BaseResponse.Success -> {
                    processData(response.data)
                    saveDataIntoDB()
                    useCase.saveCurrentTimeStamp(
                        System.currentTimeMillis()
                    )
                }
                else -> {
                    errorMessage.value = SOMETHING_WENT_WRONG
                }
            }
            showProgressBar.set(false)
        }
    }

    private fun processData(response: String) {
        val jsonData = JSONObject(response)
        try {
            val quotesData = jsonData.getJSONObject("quotes")
            val listData = ArrayList<QuoteEntity>()
            quotesData.keys().forEach {
                val currencyCode = it
                val conversionRate = quotesData.getDouble(currencyCode)
                listData.add(
                    QuoteEntity(
                        currencyCode.replaceFirst("USD", ""),
                        conversionRate
                    )
                )
            }
            listOfQuotes.value = listData
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun saveDataIntoDB() {
        listOfQuotes.value?.let {
            if (it.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.Main) {
                    withContext(Dispatchers.IO) {
                        useCase.saveAllExchangeRates(listOfQuotes = it)
                    }
                }
            }
        }
    }

    fun getConvertedRate(exchangeRate: Double, quoteEntity: QuoteEntity): Double {
        return exchangeRate / quoteEntity.exchangeRate
    }
}
